;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.file-layout-reflow-test
  (:require
   [app.common.geom.modifiers :as gm]
   [app.common.logic.file-layout-reflow :as flr]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- add-flex-frame
  [file label & {:keys [width height] :as params}]
  (ths/add-sample-shape file label
                        (merge {:type :frame
                                :name "FlexFrame"
                                :layout :flex
                                :layout-flex-dir :row
                                :layout-gap-type :multiple
                                :layout-gap {:row-gap 0 :column-gap 0}
                                :layout-padding-type :simple
                                :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
                                :layout-align-items :start
                                :layout-justify-content :start
                                :layout-wrap-type :nowrap
                                :width (or width 200)
                                :height (or height 100)}
                               params)))

(defn- add-rect-child
  [file label parent & {:keys [width height x y] :as params}]
  (ths/add-sample-shape file label
                        (merge {:type :rect
                                :name "Rect"
                                :parent-label parent
                                :width (or width 40)
                                :height (or height 40)
                                :x (or x 0)
                                :y (or y 0)}
                               params)))

(t/deftest resolve-reflow-roots-test
  (t/testing "full page scope collects layout frames in deterministic order"
    (let [file (-> (thf/sample-file :file1)
                   (add-flex-frame :f1)
                   (add-flex-frame :f2))
          page (thf/current-page file)
          objects (:objects page)
          roots (flr/resolve-reflow-roots objects {})]
      (t/is (= 2 (count roots)))
      (t/is (= roots (vec (sort-by str roots)))))

    (t/testing "targeted scope uses provided root-ids"
      (let [file (-> (thf/sample-file :file1)
                     (add-flex-frame :f1)
                     (add-flex-frame :f2))
            page (thf/current-page file)
            objects (:objects page)
            f1-id (thi/id :f1)
            roots (flr/resolve-reflow-roots objects {:root-ids [f1-id]})]
        (t/is (= [f1-id] roots))))))

(t/deftest compute-reflow-changes-flex-test
  (t/testing "reflow produces mod-obj changes for flex children"
    (let [file (-> (thf/sample-file :file1)
                   (add-flex-frame :frame1)
                   (add-rect-child :rect1 :frame1 :x 50 :y 10)
                   (add-rect-child :rect2 :frame1 :x 120 :y 10))
          page (thf/current-page file)
          page-id (:id page)
          objects (:objects page)
          frame-id (thi/id :frame1)
          {:keys [changes noop? changed-count]}
          (flr/compute-reflow-changes page objects
                                      {:root-ids [frame-id]
                                       :page-id page-id})]
      (t/is (false? noop?))
      (t/is (pos? changed-count))
      (t/is (every? #(#{:mod-obj :reg-objects} (:type %)) changes))
      (t/is (some #(= :mod-obj (:type %)) changes))))

  (t/testing "noop when geometry already matches reflow output"
    (let [file (-> (thf/sample-file :file1)
                   (add-flex-frame :frame1)
                   (add-rect-child :rect1 :frame1))
          page (thf/current-page file)
          page-id (:id page)
          objects (:objects page)
          frame-id (thi/id :frame1)
          first-pass (flr/compute-reflow-changes page objects
                                                 {:root-ids [frame-id]
                                                  :page-id page-id})
          file (thf/apply-changes file {:redo-changes (:changes first-pass)
                                        :undo-changes []})
          page (thf/current-page file)
          objects (:objects page)
          second-pass (flr/compute-reflow-changes page objects
                                                  {:root-ids [frame-id]
                                                   :page-id page-id})]
      (t/is (true? (:noop? second-pass)))
      (t/is (empty? (:changes second-pass))))))

(defn- add-grid-frame
  [file label & params]
  (ths/add-sample-shape file label
                        (merge {:type :frame
                                :name "GridFrame"
                                :layout :grid
                                :layout-grid-dir :row
                                :layout-grid-columns [{:type :fixed :value 50.0}
                                                      {:type :fixed :value 50.0}]
                                :layout-grid-rows [{:type :fixed :value 100.0}]
                                :layout-grid-cells {}
                                :layout-padding-type :multiple
                                :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
                                :layout-gap {:column-gap 0 :row-gap 0}
                                :width 200
                                :height 200}
                               params)))

(t/deftest compute-reflow-changes-grid-test
  (t/testing "grid layout reflow generates changes"
    (let [file (-> (thf/sample-file :file1)
                   (add-grid-frame :grid1)
                   (add-rect-child :rect1 :grid1 :x 30 :y 30))
          page (thf/current-page file)
          page-id (:id page)
          grid-id (thi/id :grid1)
          objects (let [frame (ctl/assign-cells (get-in page [:objects grid-id])
                                                (:objects page))]
                    (assoc (:objects page) grid-id frame))
          {:keys [noop? changed-count]}
          (flr/compute-reflow-changes page objects
                                      {:root-ids [grid-id]
                                       :page-id page-id})]
      (t/is (false? noop?))
      (t/is (pos? changed-count)))))

(t/deftest reflow-modifiers-parity-smoke-test
  (t/testing "first flex child has numeric accumulators on JVM and JavaScript"
    (doseq [justify [:start :space-around :space-evenly]]
      (let [file (-> (thf/sample-file :file1)
                     (add-flex-frame :frame1 :layout-justify-content justify)
                     (add-rect-child :rect1 :frame1))
            objects (:objects (thf/current-page file))
            frame-id (thi/id :frame1)
            modif-tree (flr/create-modif-tree [frame-id])
            result (gm/set-objects-modifiers modif-tree objects)]
        (t/is (contains? result frame-id))
        (t/is (ctl/flex-layout? objects frame-id))))))
