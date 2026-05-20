;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-layout
  (:require
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.logic.file-layout-reflow :as flr]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.pages-list :as ctpl]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.util.blob :as blob]
   [app.http.errors :as errors]
   [app.loggers.webhooks :as webhooks]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-update :as files-update]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.util.services :as sv]
   [clojure.set :as set]))

(def ^:private schema:recompute-file-layout
  [:map {:title "recompute-file-layout"}
   [:id ::sm/uuid]
   [:session-id ::sm/uuid]
   [:revn {:min 0} ::sm/int]
   [:vern {:min 0} ::sm/int]
   [:features {:optional true} ::cfeat/features]
   [:page-id {:optional true} ::sm/uuid]
   [:root-ids {:optional true} [:vector {:max 100} ::sm/uuid]]
   [:skip-validate {:optional true} ::sm/boolean]])

(def ^:private schema:recompute-file-layout-result
  [:map {:title "recompute-file-layout-result"}
   [:file-id ::sm/uuid]
   [:revn {:min 0} ::sm/int]
   [:vern {:min 0} ::sm/int]
   [:noop? ::sm/boolean]
   [:changed-count {:min 0} ::sm/int]
   [:root-count {:min 0} ::sm/int]])

(defn- resolve-page
  [file-data page-id]
  (or (when page-id (ctpl/get-page file-data page-id))
      (ctpl/get-last-page file-data)))

(defn- recompute-file-layout*
  [{:keys [::mtx/metrics] :as cfg}
   {:keys [profile-id file team features session-id skip-validate
           page-id root-ids] :as params}]

  (let [file-id   (:id file)
        file-data (-> (:data file)
                      (blob/decode)
                      (assoc :id file-id))
        file-data (:data (fdata/realize cfg (assoc file :data file-data)))
        page      (resolve-page file-data page-id)
        _         (when-not page
                    (ex/raise :type :validation
                              :code :page-not-found
                              :hint "Unable to find page for layout reflow."
                              :page-id page-id))
        page-id   (:id page)
        objects   (:objects page)
        {:keys [changes changed-count noop? root-count]}
        (flr/compute-reflow-changes page objects
                                    {:root-ids root-ids
                                     :page-id page-id})]

    (when (> changed-count flr/default-max-changed-shapes)
      (ex/raise :type :validation
                :code :reflow-too-many-changes
                :hint (str "Layout reflow would change too many shapes (max "
                          flr/default-max-changed-shapes ").")
                :context {:changed-count changed-count}))

    (mtx/run! metrics {:id :recompute-file-layout-roots :inc root-count})
    (mtx/run! metrics {:id :recompute-file-layout-changed :inc changed-count})

    (if noop?
      (do
        (mtx/run! metrics {:id :recompute-file-layout-noop :inc 1})
        {:file-id (:id file)
         :revn (:revn file)
         :vern (:vern file)
         :noop? true
         :changed-count 0
         :root-count root-count})

      (let [result (files-update/apply-prepared-changes!
                    cfg
                    (-> params
                        (assoc :changes changes)
                        (assoc :skip-validate skip-validate)))]
        (mtx/run! metrics {:id :recompute-file-layout-applied :inc 1})
        {:file-id (:id file)
         :revn (:revn result)
         :vern (:vern file)
         :noop? false
         :changed-count changed-count
         :root-count root-count}))))

(sv/defmethod ::recompute-file-layout
  {::climit/id [[:recompute-file-layout/by-profile ::rpc/profile-id]
                [:recompute-file-layout/global]
                [:update-file/by-profile ::rpc/profile-id]
                [:update-file/global]]

   ::webhooks/event? true
   ::webhooks/batch-timeout (ct/duration "2m")
   ::webhooks/batch-key (webhooks/key-fn ::rpc/profile-id :id)

   ::sm/params schema:recompute-file-layout
   ::sm/result schema:recompute-file-layout-result
   ::doc/module :files
   ::doc/added "2.17"
   ::db/transaction true}
  [{:keys [::mtx/metrics ::db/conn] :as cfg}
   {:keys [::rpc/profile-id id session-id] :as params}]

  (files/check-edition-permissions! conn profile-id id)
  (db/xact-lock! conn id)

  (let [file     (files-update/get-file cfg id)
        team     (teams/get-team conn
                                 :profile-id profile-id
                                 :team-id (:team-id file))
        features (-> (cfeat/get-team-enabled-features cf/flags team)
                     (cfeat/check-client-features! (:features params))
                     (cfeat/check-file-features! (:features file)))
        params   (-> params
                     (assoc :profile-id profile-id)
                     (assoc :features (set/difference features cfeat/frontend-only-features))
                     (assoc :team team)
                     (assoc :file file))
        tpoint   (ct/tpoint)]

    (when (not= (:vern params) (:vern file))
      (ex/raise :type :validation
                :code :vern-conflict
                :hint "A different version has been restored for the file."
                :context {:incoming-revn (:revn params)
                          :stored-revn (:revn file)}))

    (when (> (:revn params) (:revn file))
      (ex/raise :type :validation
                :code :revn-conflict
                :hint "The incoming revision number is greater that stored version."
                :context {:incoming-revn (:revn params)
                          :stored-revn (:revn file)}))

    (when-let [root-ids (:root-ids params)]
      (when (> (count root-ids) flr/default-max-roots)
        (ex/raise :type :validation
                  :code :reflow-too-many-roots
                  :hint (str "Too many layout roots (max " flr/default-max-roots ").")
                  :context {:roots (count root-ids)})))

    (binding [l/*context* (some-> (meta params)
                                  (get :app.http/request)
                                  (errors/request->context))]
      (-> (recompute-file-layout* cfg params)
          (rph/with-defer #(let [elapsed (tpoint)]
                             (l/trace :hint "recompute-file-layout"
                                      :time (ct/format-duration elapsed))))))))
