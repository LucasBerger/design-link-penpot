(ns frontend-tests.data.workspace-shape-layout-test
  (:require
   [app.common.geom.bounds-map :as gbm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.min-size-layout :as minimum]
   [app.main.data.workspace.design-link :as dl]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.shape-layout :as sut]
   [app.main.features :as features]
   [cljs.test :as t :include-macros true]))

(t/deftest linked-layout-keeps-the-shared-sizing-engine
  (with-redefs [features/active-feature? (constantly true)
                dwm/apply-wasm-modifiers (fn [& _] :wasm)
                dwm/apply-modifiers (fn ([] nil) ([options] options))]
    (doseq [metadata [{:design-link {:project-id "project"}}
                      {"design-link" {"project-id" "project"}}]]
      (let [state {:workspace-data {:plugin-data metadata}}
            event (sut/layout-reflow-event state :page {:shape :reflow} :undo)]
        (t/is (= {:page-id :page :modifiers {:shape :reflow}
                  :stack-undo? true :ignore-touched true :undo-group :undo :ignore-snap-pixel true}
                 event))))
    (t/is (= :wasm (sut/layout-reflow-event {} :page {} :undo)))
    (t/is (= :wasm (sut/layout-reflow-event {:workspace-data {:plugin-data {:other {}}}} :page {} :undo))))
  (with-redefs [features/active-feature? (constantly false)
                dwm/apply-modifiers (fn ([] nil) ([options] options))]
    (t/is (= :page (:page-id (sut/layout-reflow-event {} :page {} nil))))))

(t/deftest file-identity-remains-independent-of-the-sidebar
  (t/is (nil? (dl/file-meta nil)))
  (t/is (nil? (dl/file-meta {:plugin-data {:design-link {:project-id 1}}})))
  (t/is (= {:project-id "project" :dashboard-base-url "https://dashboard.example" :api-base-url "https://api.example"}
           (dl/file-meta {:plugin-data {"design-link" {"project-id" "project"
                                                       "dashboard-base-url" "https://dashboard.example"
                                                       "api-base-url" "https://api.example"}}}))))

(defn- box
  [id x y width height]
  {:id id :type :frame :x x :y y :width width :height height
   :points [(gpt/point x y) (gpt/point (+ x width) y)
            (gpt/point (+ x width) (+ y height)) (gpt/point x (+ y height))]})

(t/deftest grid-fill-minimum-includes-child-content-and-padding
  (let [card (assoc (box :card 0 0 300 202)
                    :layout :flex :layout-flex-dir :column :layout-align-items :start
                    :layout-item-v-sizing :fill :layout-item-h-sizing :fill
                    :layout-padding {:p1 40 :p2 40 :p3 40 :p4 40}
                    :layout-padding-type :multiple :shapes [:heading :paragraph])
        heading (assoc (box :heading 40 40 220 24) :parent-id :card)
        paragraph (assoc (box :paragraph 40 72 220 90) :parent-id :card)
        objects {:card card :heading heading :paragraph paragraph}
        bounds (gbm/objects->bounds-map objects)]
    (t/is (= 202 (minimum/child-min-height card @(get bounds :card) bounds objects true)))
    (t/is (= 0.01 (minimum/child-min-height card @(get bounds :card) bounds objects false)))
    (let [smaller (assoc (box :paragraph 40 72 220 30) :parent-id :card)
          objects (assoc objects :paragraph smaller)
          bounds (gbm/objects->bounds-map objects)]
      (t/is (= 142 (minimum/child-min-height card @(get bounds :card) bounds objects true))))))
