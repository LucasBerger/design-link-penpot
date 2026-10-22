;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.logic.file-layout-reflow
  "Server-side layout reflow computation with parity to the frontend
  commit pipeline (see backend/docs/server-layout-reflow-parity.md)."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.shapes :as cls]
   [app.common.math :as mth]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]))

;; Mirrors `transform-attrs` from frontend modifiers.cljs (commit path).
(def transform-attrs
  #{:selrect
    :points
    :x
    :y
    :r1
    :r2
    :r3
    :r4
    :shadow
    :blur
    :strokes
    :width
    :height
    :content
    :transform
    :transform-inverse
    :rotation
    :flip-x
    :flip-y
    :grow-type
    :position-data
    :layout-gap
    :layout-padding
    :layout-item-h-sizing
    :layout-item-max-h
    :layout-item-max-w
    :layout-item-min-h
    :layout-item-min-w
    :layout-item-v-sizing
    :layout-padding-type
    :layout-item-margin
    :layout-item-margin-type
    :layout-grid-cells
    :layout-grid-columns
    :layout-grid-rows})

(def default-max-roots
  "Maximum layout roots processed in one reflow call."
  100)

(def default-max-changed-shapes
  "Fail-fast limit for generated :mod-obj changes."
  10000)

(defn- check-delta
  "Component-copy relative position check (ported from frontend modifiers.cljs)."
  [shape root transformed-shape transformed-root]
  (let [shape-delta
        (when root
          (gpt/point (- (gsh/left-bound shape) (gsh/left-bound root))
                     (- (gsh/top-bound shape) (gsh/top-bound root))))

        transformed-shape-delta
        (when transformed-root
          (gpt/point (- (gsh/left-bound transformed-shape) (gsh/left-bound transformed-root))
                     (- (gsh/top-bound transformed-shape) (gsh/top-bound transformed-root))))

        distance
        (if (and shape-delta transformed-shape-delta)
          (gpt/distance-vector shape-delta transformed-shape-delta)
          (gpt/point 0 0))

        rotation-delta
        (if (and (some? (:rotation shape)) (some? (:rotation shape)))
          (- (:rotation transformed-shape) (:rotation shape))
          0)

        selrect (:selrect shape)
        transformed-selrect (:selrect transformed-shape)]

    (and (and (< (:x distance) 1) (< (:y distance) 1))
         (mth/close? (:width selrect) (:width transformed-selrect))
         (mth/close? (:height selrect) (:height transformed-selrect))
         (mth/close? rotation-delta 0))))

(defn calculate-ignore-tree
  "Map shape-id -> ignore-geometry? for component copies (commit parity)."
  [modif-tree objects]
  (letfn [(get-ignore-tree
            ([ignore-tree shape]
             (let [shape-id (dm/get-prop shape :id)
                   transformed-shape (gsh/transform-shape shape (dm/get-in modif-tree [shape-id :modifiers]))
                   root
                   (if (:component-root shape)
                     shape
                     (ctn/get-component-shape objects shape {:allow-main? true}))
                   transformed-root
                   (if (:component-root shape)
                     transformed-shape
                     (gsh/transform-shape root (dm/get-in modif-tree [(:id root) :modifiers])))]
               (get-ignore-tree ignore-tree shape transformed-shape root transformed-root)))

            ([ignore-tree shape root transformed-root]
             (let [shape-id (dm/get-prop shape :id)
                   transformed-shape (gsh/transform-shape shape (dm/get-in modif-tree [shape-id :modifiers]))]
               (get-ignore-tree ignore-tree shape transformed-shape root transformed-root)))

            ([ignore-tree shape transformed-shape root transformed-root]
             (let [shape-id (dm/get-prop shape :id)
                   ignore-tree
                   (cond-> ignore-tree
                     (and (some? root) (ctk/in-component-copy? shape))
                     (assoc shape-id
                            (check-delta shape root transformed-shape transformed-root)))

                   set-child
                   (fn [ignore-tree child]
                     (get-ignore-tree ignore-tree child root transformed-root))]

               (->> (:shapes shape)
                    (map (d/getf objects))
                    (reduce set-child ignore-tree)))))]

    (->> (keys modif-tree)
         (map #(get objects %))
         (remove nil?)
         (reduce get-ignore-tree nil))))

(defn create-modif-tree
  [ids]
  (into {} (map #(vector % {:modifiers (ctm/reflow-modifiers)})) ids))

(defn- sort-ids
  [ids]
  (vec (sort-by str ids)))

(defn resolve-reflow-roots
  "Resolve layout root ids for a reflow scope."
  [objects {:keys [root-ids]}]
  (if (seq root-ids)
    (->> root-ids
         (remove uuid/zero?)
         (filter #(contains? objects %))
         distinct
         sort-ids)
    (->> objects
         vals
         (filter ctl/any-layout?)
         (map :id)
         sort-ids)))

(defn compute-reflow-changes
  "Run reflow modifiers and return minimal file changes.

  Options:
  - :root-ids — optional targeted layout roots
  - :page-id  — stored on :mod-obj changes

  Returns a map:
  - :changes       — vector of change maps (redo only)
  - :changed-count — number of :mod-obj changes
  - :noop?         — true when no geometry diff"
  [_page objects {:keys [root-ids page-id]}]
  (let [root-ids (resolve-reflow-roots objects {:root-ids root-ids})]
    (if (empty? root-ids)
      {:changes [] :changed-count 0 :noop? true :root-count 0}
      (let [modif-tree (create-modif-tree root-ids)
            object-modifiers (gm/set-objects-modifiers modif-tree objects)
            ids (->> (keys object-modifiers)
                     (remove uuid/zero?)
                     sort-ids)
            ignore-tree (calculate-ignore-tree object-modifiers objects)
            update-shape
            (fn [shape]
              (let [shape-id (dm/get-prop shape :id)
                    modifiers (dm/get-in object-modifiers [shape-id :modifiers])]
                (gsh/transform-shape shape modifiers)))
            changes
            (-> (pcb/empty-changes nil page-id)
                (pcb/with-objects objects)
                (cls/generate-update-shapes ids
                                            update-shape
                                            objects
                                            {:attrs transform-attrs
                                             :ignore-tree ignore-tree
                                             :ignore-touched true
                                             :with-objects? false})
                (pcb/resize-parents ids))
            redo-changes (:redo-changes changes)
            changed-count (count (filter #(= :mod-obj (:type %)) redo-changes))]
        {:changes redo-changes
         :changed-count changed-count
         :noop? (empty? redo-changes)
         :root-count (count root-ids)}))))
