;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.data.workspace.design-link
  (:require [app.common.data :as d]))

(defn- getdl
  [data key]
  (when (map? data)
    (or (get data key) (get data (name key)))))

(defn file-meta
  "Source-linked file identity; Transit can retain string plugin-data keys."
  [data]
  (let [metadata (getdl (:plugin-data data) :design-link)
        project-id (getdl metadata :project-id)]
    (when (string? project-id)
      {:project-id project-id
       :dashboard-base-url (d/nilv (getdl metadata :dashboard-base-url) "http://localhost:3000")
       :api-base-url (d/nilv (getdl metadata :api-base-url) "http://localhost:3001")})))

(defn rotation-editable?
  "Linked files default to the backend's restricted direct-edit contract."
  [data]
  (or (nil? (file-meta data))
      (= "true" (getdl (getdl (:plugin-data data) :design-link) :rotation-editing))))

(defn structural-editable?
  "Structural editing is deferred for linked files, including older imports."
  [data]
  (or (nil? (file-meta data))
      (= "true" (getdl (getdl (:plugin-data data) :design-link) :structural-editing))))
