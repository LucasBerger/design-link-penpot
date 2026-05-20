;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.layout-engine.api
  "Standalone Node-friendly entry points for the Penpot layout-reflow logic.

  Built as an :esm :runtime :node shadow-cljs module (build target
  :layout-engine in shadow-cljs.edn). Imported by the Design Link backend
  so reflow runs under V8 with the same arithmetic semantics as the
  Penpot editor (JS nil/0 coercion), instead of hitting the JVM
  `recompute-file-layout` RPC where Clojure throws NPEs on nil arithmetic.

  Wire format on the JS<->CLJS boundary is transit+json: the backend
  passes through the raw transit text from `get-file` and we hand back
  transit-encoded changes ready to feed to `update-file`. This keeps
  Penpot's own transit handlers (UUID, point, selrect, modifiers, …) in
  the loop without any custom JS<->Clojure conversion logic on our side."
  (:require
   [app.common.logic.file-layout-reflow :as flr]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]))

(defn- ->uuid
  [x]
  (cond
    (nil? x) nil
    (uuid? x) x
    :else    (uuid/uuid x)))

(defn- ->root-ids
  [^js raw]
  (when (some? raw)
    (->> (js->clj raw)
         (keep ->uuid)
         (vec))))

(defn ^{:export true} compute-reflow
  "Compute layout reflow changes for one page.

  Input (plain JS object):
    - fileTransit  string  — application/transit+json text returned by
                              the Penpot `get-file` RPC. Passed through
                              unchanged from the backend.
    - pageId       string  — UUID of the target page.
    - rootIds      ?string[] — optional UUIDs of importer-touched roots
                              to scope reflow to. If absent / empty, all
                              auto-layout shapes on the page are reflowed
                              (same default as the frontend).

  Output (plain JS object):
    - changesTransit string — application/transit+json of the redo
                              changes vector (mod-obj entries) ready to
                              hand off to `update-file`.
    - changedCount   number — number of :mod-obj entries in the changes.
    - noop           boolean — true when reflow produced no geometric
                              diff (frontend equivalent: nothing to commit).
    - rootCount      number — number of resolved layout roots.

  Throws if the page can't be located in the file."
  [^js input]
  (let [file-text (.-fileTransit input)
        page-id   (->uuid (.-pageId input))
        root-ids  (->root-ids (.-rootIds input))
        file      (t/decode-str file-text)
        data      (:data file)
        page      (or (get-in data [:pages-index page-id])
                      (throw (ex-info "Page not found in file data"
                                      {:page-id (str page-id)
                                       :available-pages
                                       (->> (:pages-index data) keys (mapv str))})))
        objects   (:objects page)
        result    (flr/compute-reflow-changes
                   page objects
                   {:root-ids root-ids
                    :page-id  page-id})]
    #js {:changesTransit (t/encode-str (vec (:changes result)))
         :changedCount   (:changed-count result)
         :noop           (:noop? result)
         :rootCount      (:root-count result)}))

(defn ^{:export true} read-penpot-file
  "Helper exposed for tests / debugging.
  Decode a transit+json file payload into a CLJS structure round-tripped
  back through transit. Useful to verify that the boundary preserves
  Penpot transit handlers correctly."
  [file-transit]
  (let [decoded (t/decode-str file-transit)]
    #js {:roundtripTransit (t/encode-str decoded)}))

(defn ^{:export true} build-update-changes
  "Convenience: take the changesTransit output from `compute-reflow` and
  wrap it in the shape expected by `update-file`. The backend already
  knows how to call update-file directly, this is mostly for symmetry."
  [^js input]
  (let [changes-text (.-changesTransit input)
        changes      (t/decode-str changes-text)]
    #js {:updateChangesTransit
         (t/encode-str {:changes changes})}))

(defn ^{:export true} build-update-file-params
  "Build a full `update-file` RPC params object, transit-encoded.

  We need to round-trip through transit (not JSON) because the change
  vector contains Penpot record types (Rect, Point, …) whose
  server-side malli schemas reject plain maps:

      :app.common.geom.point/point — :value {:x 56 :y 16} — invalid

  The frontend's commit pipeline avoids this by serialising the change
  vector with the Penpot transit writer (which tags Rect/Point records
  with their proper transit types). We do the same here: take the
  changesTransit text from `compute-reflow`, decode → preserve the
  record types → re-encode together with the rest of the update-file
  params. The Design Link backend then ships the resulting string as a
  raw `application/transit+json` body, bypassing its own JSON path.

  Input (JS object):
    - fileId         string — UUID of the file
    - sessionId      string — client session UUID
    - revn           number — last known revn (will be checked server-side)
    - vern           number — last known vern
    - changesTransit string — output of `compute-reflow`
    - skipValidate   ?boolean — defaults to true (same as the editor's
                                throttled commit, matches existing
                                Design Link behaviour)

  Output (JS object):
    - paramsTransit  string — full RPC body, ready to POST."
  [^js input]
  (let [changes-text (.-changesTransit input)
        changes      (t/decode-str changes-text)
        params       {:id            (->uuid (.-fileId input))
                      :session-id    (->uuid (.-sessionId input))
                      :revn          (.-revn input)
                      :vern          (.-vern input)
                      :changes       changes
                      :skip-validate (if (some? (.-skipValidate input))
                                       (boolean (.-skipValidate input))
                                       true)}]
    #js {:paramsTransit (t/encode-str params)}))
