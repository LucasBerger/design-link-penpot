(ns frontend-tests.workspace-design-link-test
  (:require
   [app.main.ui.workspace.design-link :as sut]
   [cljs.test :as t :include-macros true]))

(defn- page
  [name]
  {:name name :id (str "id-" name)})

(t/deftest design-link-meta-from-fdata
  (t/is (nil? (sut/design-link-meta-from-fdata nil)))
  (t/is (nil? (sut/design-link-meta-from-fdata {})))
  (let [fdata {:plugin-data {:design-link {"project-id" "p1"
                                            "dashboard-base-url" "https://dash.example"
                                            "api-base-url" "https://api.example"}}}]
    (t/is (= {:project-id "p1"
              :dashboard-base-url "https://dash.example"
              :api-base-url "https://api.example"}
             (sut/design-link-meta-from-fdata fdata))))
  (let [fdata {:plugin-data {:design-link {:project-id "p2"}}}]
    (t/is (= {:project-id "p2"
              :dashboard-base-url "http://localhost:3000"
              :api-base-url "http://localhost:3001"}
             (sut/design-link-meta-from-fdata fdata)))))

(t/deftest page-kind
  (t/is (= :sandbox (sut/page-kind (page "__sandbox__"))))
  (t/is (= :view (sut/page-kind (page "view:Login Flow"))))
  (t/is (= :page (sut/page-kind (page "page:/"))))
  (t/is (= :page (sut/page-kind (page "page:/login"))))
  (t/is (= :other (sut/page-kind (page "Some Page")))))

(t/deftest page-route
  (t/is (nil? (sut/page-route (page "__sandbox__"))))
  (t/is (= "Login Flow" (sut/page-route (page "view:Login Flow"))))
  (t/is (= "/" (sut/page-route (page "page:/"))))
  (t/is (= "/login" (sut/page-route (page "page:/login")))))

(t/deftest page-display-label
  (t/is (= "Sandbox" (sut/page-display-label (page "__sandbox__"))))
  (t/is (= "Login Flow" (sut/page-display-label (page "view:Login Flow"))))
  (t/is (= "home" (sut/page-display-label (page "page:/"))))
  (t/is (= "login" (sut/page-display-label (page "page:/login")))))
