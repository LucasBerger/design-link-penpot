;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
;;
;; Design Link fork: workspace helpers and alternate sidebar.

(ns app.main.ui.workspace.design-link
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.common :as dcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.workspace.sidebar.layers :refer [layers-toolbox*]]
   [cuerdas.core :as str]
   [goog.object :as gobj]
   [rumext.v2 :as mf]))

(defn- getdl
  [dl k]
  (when (map? dl)
    (let [nk (name k)]
      (or (get dl k) (get dl nk) (get dl (keyword nk))))))

(defn design-link-meta-from-fdata
  "File `data` blob (e.g. from `refs/workspace-data`). Returns nil if not a Design Link file."
  [fdata]
  (when (some? fdata)
    (let [pd (:plugin-data fdata)
          dl (or (:design-link pd) (get pd "design-link"))]
      (when-let [pid (getdl dl :project-id)]
        (when (string? pid)
          {:project-id pid
           :dashboard-base-url (d/nilv (getdl dl :dashboard-base-url) "http://localhost:3000")
           :api-base-url (d/nilv (getdl dl :api-base-url) "http://localhost:3001")})))))

(defn design-link-file?
  [fdata]
  (some? (design-link-meta-from-fdata fdata)))

(defn- find-page-id-by-name
  [fdata page-name]
  (->> (:pages-index fdata)
       (some (fn [[_id page]]
               (when (= (:name page) page-name)
                 (:id page))))))

(defn- dl-route-pages
  [fdata]
  (->> (:pages-index fdata)
       vals
       (filter #(str/starts-with? (:name %) "page:"))
       (sort-by :name)))

(mf/defc changes-tag*
  {::mf/private true}
  []
  (let [fdata         (mf/deref refs/workspace-data)
        meta          (design-link-meta-from-fdata fdata)
        project-id    (:project-id meta)
        api-root      (:api-base-url meta)
        change-count* (mf/use-state 0)]

    (mf/with-effect [project-id api-root]
      (if (nil? project-id)
        (fn [])
        (let [interval (js/setInterval
                        (fn []
                          (-> (js/fetch (str api-root "/api/projects/" project-id "/changes/count"))
                              (.then #(.json %))
                              (.then (fn [res]
                                       (reset! change-count* (gobj/get res "count"))))
                              (.catch (fn [_] nil))))
                        2000)]
          (fn [] (js/clearInterval interval)))))

    (when (and project-id (pos? @change-count*))
      [:div {:class (stl/css :changesTag)
             :title (dm/str @change-count* " pending changes")}
       [:span {:class (stl/css :changesTagCount)} @change-count*]
       [:span " changes"]])))

(mf/defc dl-pages-panel*
  {::mf/private true}
  [{:keys [fdata file-id]}]
  (let [pages (mf/with-memo [fdata]
                (dl-route-pages fdata))

        current-page-id
        (mf/use-ctx ctx/current-page-id)

        on-open-page
        (mf/use-fn
         (mf/deps file-id)
         (fn [page-id _event]
           (st/emit! (dcm/go-to-workspace :page-id page-id :file-id file-id))))]

    [:div {:class (stl/css :dlPanel)}
     (for [p pages]
       (let [page-id    (:id p)
             selected?  (= page-id current-page-id)
             page-label (str/replace-first (:name p) #"^page:" "")]
         [:div {:key (str page-id)
                :class (stl/css-case :dlPageRow true
                                     :selected selected?)
                :role "button"
                :tab-index 0
                :aria-current (when selected? "page")
                :on-click #(on-open-page page-id %)}
          [:span {:class (stl/css :dlPageName)} page-label]]))]))

(mf/defc design-link-sidebar*
  {::mf/private true}
  [{:keys [width layout file-id fdata tabs-action-button]}]
  (let [selected-tab* (mf/use-state "dl-pages")
        selected-tab   (deref selected-tab*)

        on-tab-change
        (mf/use-fn #(reset! selected-tab* %))

        tabs
        (mf/with-memo []
          [{:label "Sandbox"
            :id "dl-sandbox"}
           {:label "Views"
            :id "dl-views"}
           {:label "Pages"
            :id "dl-pages"}
           {:label "Nodes"
            :id "dl-nodes"}])

        on-open-sandbox
        (mf/use-fn
         (mf/deps fdata file-id)
         (fn []
           (when-let [pid (find-page-id-by-name fdata "__sandbox__")]
             (st/emit! (dcm/go-to-workspace :page-id pid :file-id file-id)))))]

    [:div {:class (stl/css :settingsBarContent)}
     [:> tab-switcher* {:tabs tabs
                        :default "dl-pages"
                        :selected selected-tab
                        :on-change on-tab-change
                        :class (stl/css :leftSidebarTabs)
                        :action-button-position "start"
                        :action-button tabs-action-button}

      (case selected-tab
        "dl-sandbox"
        [:div {:class (stl/css :dlPanel)}
         [:button {:type "button"
                   :class (stl/css :dlSandboxBtn)
                   :on-click on-open-sandbox}
          "Open __sandbox__ page"]]

        "dl-views"
        [:div {:class (stl/css :dlPlaceholder)}
         "Multi-page flows (coming soon)."]

        "dl-pages"
        [:> dl-pages-panel* {:fdata fdata :file-id file-id}]

        "dl-nodes"
        [:div {:class (stl/css :dlNodesWrap)}
         [:> layers-toolbox* {:size-parent width}]]

        [:div {:class (stl/css :dlNodesWrap)}
         [:> layers-toolbox* {:size-parent width}]])]]))
