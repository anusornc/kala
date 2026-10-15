(ns build
  "Release packaging: jar + pom with EPL-1.0 notices. Version comes from
  the environment RELEASE_VERSION (set by the release workflow from the
  git tag) or defaults to 0.1.0-SNAPSHOT."
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.anusornc/kala)
(def version (or (System/getenv "RELEASE_VERSION") "0.1.0-SNAPSHOT"))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"})
  (println "Cleaned target/"))

(defn- write-pom* []
  (b/write-pom
   {:class-dir class-dir
    :lib lib
    :version version
    :basis basis
    :src-dirs ["src"]
    :pom-data [[:description "An independent, clean-room, open-source reimplementation of Datomic's architecture in Clojure: transactor, embedded peer, remote client, Datalog, pull, time travel."]
               [:url "https://github.com/anusornc/kala"]
               [:licenses
                [:license
                 [:name "Eclipse Public License - v 1.0"]
                 [:url "https://www.eclipse.org/legal/epl-v10.html"]]]
               [:scm [:url "https://github.com/anusornc/kala"]
                [:connection "scm:git:git://github.com/anusornc/kala.git"]
                [:developerConnection "scm:git:ssh://git@github.com/anusornc/kala.git"]]]}))

(defn jar [_]
  (clean nil)
  (write-pom*)
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/copy-file {:src "LICENSE" :target (str class-dir "/LICENSE")})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Built" jar-file))

(defn install [_]
  (jar nil)
  (b/install {:basis basis :lib lib :version version :jar-file jar-file :class-dir class-dir})
  (println "Installed" lib version))

(defn deploy [_]
  ;; credentials come from the environment: CLOJARS_USERNAME and
  ;; CLOJARS_PASSWORD (a Clojars deploy token). The repository is passed
  ;; as an explicit map — deps-deploy's string form looks the credentials
  ;; up in ~/.m2/settings.xml and NPEs when that file has no entry.
  ;; deps-deploy also wants a pom.xml file path; tools.build wrote ours
  ;; under target/classes/META-INF/maven.
  (jar nil)
  (let [pom (str class-dir "/META-INF/maven/" (namespace lib) "/" (name lib) "/pom.xml")]
    (println "Deploying" jar-file "to Clojars as" lib version)
    ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
     {:artifact jar-file
      :installer :remote
      :pom-file pom
      :repository {"clojars" {:url "https://clojars.org/repo"
                              :username (System/getenv "CLOJARS_USERNAME")
                              :password (System/getenv "CLOJARS_PASSWORD")}}})))
