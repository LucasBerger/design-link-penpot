;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.loader
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(defn- get-tips
  []
  [{:title (tr "loader.tips.01.title")
    :message (tr "loader.tips.01.message")}
   {:title (tr "loader.tips.02.title")
    :message (tr "loader.tips.02.message")}
   {:title (tr "loader.tips.03.title")
    :message (tr "loader.tips.03.message")}
   {:title (tr "loader.tips.04.title")
    :message (tr "loader.tips.04.message")}
   {:title (tr "loader.tips.05.title")
    :message (tr "loader.tips.05.message")}
   {:title (tr "loader.tips.06.title")
    :message (tr "loader.tips.06.message")}
   {:title (tr "loader.tips.07.title")
    :message (tr "loader.tips.07.message")}
   {:title (tr "loader.tips.08.title")
    :message (tr "loader.tips.08.message")}
   {:title (tr "loader.tips.09.title")
    :message (tr "loader.tips.09.message")}
   {:title (tr "loader.tips.10.title")
    :message (tr "loader.tips.10.message")}])

(mf/defc loader-icon*
  {::mf/private true}
  [{:keys [width height title]}]
  (let [size (or height width 64)]
    [:img {:src "/images/dl-icon.png"
           :alt (or title "Loading")
           :class (stl/css :loader-img)
           :style {:width size :height size}}]))

(def ^:private schema:loader
  [:map
   [:class {:optional true} :string]
   [:width {:optional true} :int]
   [:height {:optional true} :int]
   [:title {:optional true} :string]
   [:overlay {:optional true} :boolean]
   [:file-loading {:optional true} :boolean]])

(mf/defc loader*
  {::mf/schema schema:loader}
  [{:keys [class width height title overlay children file-loading] :rest props}]
  (let [width  (or width 64)
        height (or height 64)

        class  (dm/str (d/nilv class "") " "
                       (stl/css-case :wrapper true
                                     :wrapper-overlay overlay
                                     :file-loading file-loading))

        title  (or title (tr "labels.loading"))
        tips   (mf/use-memo get-tips)

        tip*   (mf/use-state nil)
        tip    (deref tip*)]

    (mf/with-effect [file-loading tips]
      (when file-loading
        (let [sub (->> (rx/timer 1000 4000)
                       (rx/subs! #(reset! tip* (rand-nth tips))))]
          (partial rx/dispose! sub))))

    [:> :div {:class class}
     [:div {:class (stl/css :loader-content)}
      [:> loader-icon* {:title title
                        :width width
                        :height height}]
      (when (and file-loading tip)
        [:div {:class (stl/css :tips-container)}
         [:div {:class (stl/css :tip-title)}
          (get tip :title)]
         [:div {:class (stl/css :tip-message)}
          (get tip :message)]])]

     children]))
