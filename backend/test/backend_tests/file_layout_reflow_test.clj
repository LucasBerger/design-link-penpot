;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.file-layout-reflow-test
  (:require
   [app.common.features :as cfeat]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- recompute-file-layout!
  [& {:keys [profile-id file-id revn page-id root-ids] :or {revn 0}}]
  (let [params (cond-> {::th/type :recompute-file-layout
                        ::rpc/profile-id profile-id
                        :id file-id
                        :session-id (uuid/random)
                        :revn revn
                        :vern 0
                        :features cfeat/supported-features}
                 page-id (assoc :page-id page-id)
                 root-ids (assoc :root-ids root-ids))
        out (th/command! params)]
  ;; (th/print-result! out)
    (t/is (nil? (:error out)) (:error out))
    (:result out)))

(defn- update-file!
  [& {:keys [profile-id file-id changes revn] :or {revn 0}}]
  (let [params {::th/type :update-file
                ::rpc/profile-id profile-id
                :id file-id
                :session-id (uuid/random)
                :revn revn
                :vern 0
                :features cfeat/supported-features
                :changes changes}
        out (th/command! params)]
    (t/is (nil? (:error out)))
    (:result out)))

(t/deftest recompute-file-layout-lifecycle
  (let [prof     (th/create-profile* 1 {:is-active true})
        proj-id  (:default-project-id prof)
        file-id  (uuid/next)
        frame-id (uuid/next)
        rect-id  (uuid/next)
        page-id  (delay
                   (-> (th/command! {::th/type :get-file
                                     ::rpc/profile-id (:id prof)
                                     :id file-id})
                       :result
                       :data
                       :pages
                       first))]

    (t/testing "create file"
      (let [out (th/command! {::th/type :create-file
                              ::rpc/profile-id (:id prof)
                              :project-id proj-id
                              :id file-id
                              :name "layout-reflow-test"
                              :is-shared false
                              :components-v2 true})]
        (t/is (nil? (:error out)))))

    (t/testing "add flex frame and child with manual offsets"
      (update-file!
       :profile-id (:id prof)
       :file-id file-id
       :revn 0
       :changes
       [{:type :add-obj
         :page-id @page-id
         :id frame-id
         :parent-id uuid/zero
         :frame-id uuid/zero
         :obj (cts/setup-shape
               {:id frame-id
                :type :frame
                :name "Flex"
                :layout :flex
                :layout-flex-dir :row
                :layout-gap {:row-gap 0 :column-gap 0}
                :x 0 :y 0 :width 200 :height 100})}
        {:type :add-obj
         :page-id @page-id
         :id rect-id
         :parent-id frame-id
         :frame-id frame-id
         :obj (cts/setup-shape
               {:id rect-id
                :type :rect
                :name "Rect"
                :x 50
                :y 10
                :width 40
                :height 40})}]))

    (t/testing "recompute applies layout and bumps revn"
      (let [result (recompute-file-layout!
                    :profile-id (:id prof)
                    :file-id file-id
                    :revn 1
                    :page-id @page-id
                    :root-ids [frame-id])]
        (t/is (false? (:noop? result)))
        (t/is (pos? (:changed-count result)))
        (t/is (= 2 (:revn result)))))

    (t/testing "second recompute is no-op at same revn"
      (let [result (recompute-file-layout!
                    :profile-id (:id prof)
                    :file-id file-id
                    :revn 2
                    :page-id @page-id
                    :root-ids [frame-id])]
        (t/is (true? (:noop? result)))
        (t/is (= 0 (:changed-count result)))
        (t/is (= 2 (:revn result)))))

    (t/testing "vern conflict is rejected"
      (let [out (th/command! {::th/type :recompute-file-layout
                              ::rpc/profile-id (:id prof)
                              :id file-id
                              :session-id (uuid/random)
                              :revn 2
                              :vern 99
                              :features cfeat/supported-features
                              :page-id @page-id})]
        (t/is (some? (:error out)))
        (t/is (= :validation (get-in out [:error :type])))))))
