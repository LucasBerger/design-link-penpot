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
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.design-link :as dl]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.sidebar.assets :refer [assets-toolbox*]]
   [app.main.ui.workspace.sidebar.layers :refer [layers-toolbox*]]
   [app.main.ui.workspace.tokens.sidebar :refer [tokens-sidebar-tab*]]
   [cuerdas.core :as str]
   [goog.object :as gobj]
   [rumext.v2 :as mf]))

(defn design-link-meta-from-fdata
  "File `data` blob (e.g. from `refs/workspace-data`). Returns nil if not a Design Link file."
  [fdata]
  (dl/file-meta fdata))

(defn design-link-file?
  [fdata]
  (some? (design-link-meta-from-fdata fdata)))

(defn page-kind
  "Classify a Penpot page by Design Link naming convention."
  [page]
  (let [name (:name page "")]
    (cond
      (= name "__sandbox__") :sandbox
      (str/starts-with? name "view:") :view
      (str/starts-with? name "page:") :page
      :else :other)))

(defn page-route
  "Route or path segment for display. Returns nil for sandbox/other."
  [page]
  (let [name (:name page "")]
    (case (page-kind page)
      :sandbox nil
      :view (str/replace-first name #"^view:" "")
      :page (str/replace-first name #"^page:" "")
      nil)))

(defn page-display-label
  "Short human label for sidebar rows."
  [page]
  (let [name (:name page "")]
    (case (page-kind page)
      :sandbox "Sandbox"
      :view (str/replace-first name #"^view:" "")
      :page (let [route (page-route page)]
              (if (= route "/")
                "home"
                (-> route
                    (str/replace #"^/" "")
                    (str/split #"/")
                    first
                    (d/nilv route))))
      name)))

(defn- dl-pages-by-kind
  [fdata kind]
  (->> (:pages-index fdata)
       vals
       (filter #(= kind (page-kind %)))
       (sort-by :name)))

(defn- current-page
  [fdata page-id]
  (when (and fdata page-id)
    (dsh/get-page fdata page-id)))

(defn- nodes-section-title
  [fdata page-id]
  (let [page (current-page fdata page-id)]
    (if (and page (= :page (page-kind page)))
      (dm/str "Nodes (" (page-route page) ")")
      "Nodes")))

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

(mf/defc dl-collapsible-section*
  {::mf/private true}
  [{:keys [title collapsed on-toggle-collapsed class children]}]
  [:div {:class [(stl/css :dlSection) class]}
   [:> title-bar* {:collapsable true
                   :collapsed collapsed
                   :on-collapsed on-toggle-collapsed
                   :title title
                   :class (stl/css :dlSectionTitle)}]
   (when-not collapsed
     children)])

(mf/defc dl-page-list*
  {::mf/private true}
  [{:keys [pages file-id show-route? empty-label]}]
  (let [current-page-id (mf/use-ctx ctx/current-page-id)

        on-open-page
        (mf/use-fn
         (mf/deps file-id)
         (fn [page-id _event]
           (st/emit! (dcm/go-to-workspace :page-id page-id :file-id file-id))))]

    [:div {:class (stl/css :dlSectionBodyInner)}
     (if (seq pages)
       (for [p pages]
         (let [page-id   (:id p)
               selected? (= page-id current-page-id)
               label     (page-display-label p)
               route     (when show-route? (page-route p))]
           [:div {:key (str page-id)
                  :class (stl/css-case :dlPageRow true
                                       :selected selected?)
                  :role "button"
                  :tab-index 0
                  :aria-current (when selected? "page")
                  :on-click #(on-open-page page-id %)}
            [:span {:class (stl/css :dlPageName)} label]
            (when (and show-route? route)
              [:span {:class (stl/css :dlPageRoute)} route])]))
       (when empty-label
         [:div {:class (stl/css :dlPlaceholder)} empty-label]))]))

(mf/defc dl-app-panel*
  {::mf/private true}
  [{:keys [fdata file-id width]}]
  (let [sandboxes-collapsed*
        (hooks/use-persisted-state ::dl-sandboxes-collapsed true)
        views-collapsed*
        (hooks/use-persisted-state ::dl-views-collapsed true)
        pages-collapsed*
        (hooks/use-persisted-state ::dl-pages-collapsed false)
        nodes-collapsed*
        (hooks/use-persisted-state ::dl-nodes-collapsed false)

        sandboxes (mf/with-memo [fdata] (dl-pages-by-kind fdata :sandbox))
        views     (mf/with-memo [fdata] (dl-pages-by-kind fdata :view))
        pages     (mf/with-memo [fdata] (dl-pages-by-kind fdata :page))

        current-page-id (mf/use-ctx ctx/current-page-id)
        nodes-title     (mf/with-memo [fdata current-page-id]
                          (nodes-section-title fdata current-page-id))]

    [:div {:class (stl/css :dlAppPanel)}
     [:> dl-collapsible-section*
      {:title "Sandboxes"
       :collapsed @sandboxes-collapsed*
       :on-toggle-collapsed #(swap! sandboxes-collapsed* not)}
      [:> dl-page-list* {:pages sandboxes
                         :file-id file-id
                         :empty-label nil}]]

     [:> dl-collapsible-section*
      {:title "Views"
       :collapsed @views-collapsed*
       :on-toggle-collapsed #(swap! views-collapsed* not)}
      [:> dl-page-list* {:pages views
                         :file-id file-id
                         :empty-label "Multi-page flows (coming soon)."}]]

     [:> dl-collapsible-section*
      {:title "Pages"
       :collapsed @pages-collapsed*
       :on-toggle-collapsed #(swap! pages-collapsed* not)}
      [:> dl-page-list* {:pages pages
                         :file-id file-id
                         :show-route? true
                         :empty-label nil}]]

     [:> dl-collapsible-section*
      {:title nodes-title
       :collapsed @nodes-collapsed*
       :on-toggle-collapsed #(swap! nodes-collapsed* not)}
      [:div {:class (stl/css :dlNodesWrap)}
       [:> layers-toolbox* {:size-parent width}]]]]))

(mf/defc dl-design-system-panel*
  {::mf/private true}
  [{:keys [width file-id tokens-lib active-tokens resolved-active-tokens]}]
  (let [design-tokens? (features/use-feature "design-tokens/v1")]

    [:div {:class (stl/css :dlDesignSystemPanel)}
     (when design-tokens?
       [:div {:class (stl/css :dlDesignSystemTokens)}
        [:> tokens-sidebar-tab*
         {:tokens-lib tokens-lib
          :active-tokens active-tokens
          :resolved-active-tokens resolved-active-tokens}]])

     (when-not design-tokens?
       [:div {:class (stl/css :dlPlaceholder)}
        "Design tokens are not enabled for this team."])

     [:div {:class (stl/css :dlDesignSystemAssets)}
      [:> assets-toolbox* {:size (- width 58)
                           :file-id file-id}]]]))

(mf/defc design-link-sidebar*
  {::mf/private true}
  [{:keys [width file-id fdata tabs-action-button
           tokens-lib active-tokens resolved-active-tokens]}]
  (let [selected-tab* (mf/use-state "dl-app")
        selected-tab   (deref selected-tab*)

        on-tab-change
        (mf/use-fn #(reset! selected-tab* %))

        tabs
        (mf/with-memo []
          [{:label "App"
            :id "dl-app"}
           {:label "Design System"
            :id "dl-design-system"}])]

    [:div {:class (stl/css :settingsBarContent)}
     [:> tab-switcher* {:tabs tabs
                        :default "dl-app"
                        :selected selected-tab
                        :on-change on-tab-change
                        :scrollable-panel true
                        :class (stl/css :leftSidebarTabs)
                        :action-button-position "start"
                        :action-button tabs-action-button}

      (case selected-tab
        "dl-app"
        [:> dl-app-panel* {:fdata fdata
                           :file-id file-id
                           :width width}]

        "dl-design-system"
        [:> dl-design-system-panel*
         {:width width
          :file-id file-id
          :tokens-lib tokens-lib
          :active-tokens active-tokens
          :resolved-active-tokens resolved-active-tokens}]

        [:> dl-app-panel* {:fdata fdata
                           :file-id file-id
                           :width width}])]]))
