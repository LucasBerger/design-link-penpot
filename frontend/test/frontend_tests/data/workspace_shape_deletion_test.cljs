(ns frontend-tests.data.workspace-shape-deletion-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.design-link :as dl]
   [app.main.data.workspace.shapes :as sut]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(defn- state-for
  [data]
  (let [file-id (uuid/next)
        page-id (uuid/next)]
    {:current-file-id file-id
     :current-page-id page-id
     :files {file-id {:data (assoc data :pages-index {page-id {:id page-id :objects {}}})}}}))

(defn- delete-events
  [state]
  (let [events (atom [])]
    (->> (ptk/watch (sut/delete-shapes #{(uuid/next)}) state nil)
         (rx/subs! #(swap! events conj %) #(throw %)))
    @events))

(defn- deletion-mock
  [handle]
  ;; The compiled caller uses generate-delete-shapes' fixed-arity entry point.
  (fn
    ([a b c d e] (handle a b c d e nil))
    ([a b c d e f] (handle a b c d e f))))

(t/deftest linked-structural-contract-defaults-to-restricted
  (doseq [metadata [{:design-link {:project-id "project" :structural-editing "false"}}
                    {"design-link" {"project-id" "project" "structural-editing" "false"}}
                    {:design-link {:project-id "legacy"}}]]
    (t/is (false? (dl/structural-editable? {:plugin-data metadata}))))
  (t/is (true? (dl/structural-editable? {})))
  (t/is (true? (dl/structural-editable? {:plugin-data {:design-link {:project-id "future" :structural-editing "true"}}}))))

(t/deftest linked-deletion-warns-before-changing-shapes-comments-or-history
  (doseq [metadata [{:design-link {:project-id "project" :structural-editing "false"}}
                    {"design-link" {"project-id" "project" "structural-editing" "false"}}
                    {:design-link {:project-id "legacy"}}]]
    (let [state (state-for {:plugin-data metadata})
          events (with-redefs [cls/generate-delete-shapes (deletion-mock (fn [_ _ _ _ _ _] (throw (js/Error. "Deletion must not be planned"))))]
                   (delete-events state))
          result (ptk/update (first events) state)]
      (t/is (= 1 (count events)))
      (t/is (= state (dissoc result :notification)))
      (t/is (= :warning (get-in result [:notification :level])))
      (t/is (re-find #"Deleting elements is unavailable" (get-in result [:notification :content]))))))

(t/deftest ordinary-active-file-keeps-deletion-despite-another-linked-file-or-stale-workspace-data
  (let [state (-> (state-for {})
                  (assoc :workspace-data {:plugin-data {:design-link {:project-id "stale"}}})
                  (assoc-in [:files :other] {:data {:plugin-data {:design-link {:project-id "other"}}}}))
        generated (atom false)
        events (with-redefs [cls/generate-delete-shapes (deletion-mock (fn [_ _ _ _ _ _]
                                                                         (reset! generated true)
                                                                         [#{} (pcb/empty-changes)]))
                             dch/commit-changes (fn ([_] :commit-delete) ([_ _] :commit-delete))]
                 (delete-events state))]
    (t/is (true? @generated))
    (t/is (= 5 (count events)))
    (t/is (= :commit-delete (nth events 2)))))
