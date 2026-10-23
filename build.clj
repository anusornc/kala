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

;; The MCP server ships as its own artifact so the core jar gains no
;; dependency: kala-mcp carries mcp/ plus its one codec dependency and
;; declares io.github.anusornc/kala (same release train, same version).
(def mcp-lib 'io.github.anusornc/kala-mcp)
;; kala-mcp may version independently when a Clojars deploy poisons its
;; copy of a release version (0.3.0's first pom was invalid and stuck);
;; MCP_RELEASE_VERSION overrides while the kala dependency stays on the
;; main release version.
(def mcp-version (or (System/getenv "MCP_RELEASE_VERSION") version))
(def mcp-class-dir "target/mcp-classes")
(def mcp-jar-file (format "target/%s-%s.jar" (name mcp-lib) mcp-version))
(def mcp-uber-file (format "target/%s-%s-standalone.jar" (name mcp-lib) mcp-version))

(defn- licenses-pom-data []
  [[:description "The kala memory layer as an MCP server: six tools over stdio — context, record, recall, read, history, turns."]
   [:url "https://github.com/anusornc/kala"]
   [:licenses
    [:license
     [:name "Eclipse Public License - v 1.0"]
     [:url "https://www.eclipse.org/legal/epl-v10.html"]]]
   [:scm [:url "https://github.com/anusornc/kala"]
    [:connection "scm:git:git://github.com/anusornc/kala.git"]
    [:developerConnection "scm:git:ssh://git@github.com/anusornc/kala.git"]]])

(defn mcp-jar [_]
  (b/delete {:path mcp-class-dir})
  ;; the kala dependency comes in via :extra so tools.build emits ONE
  ;; <dependencies> section (a [:dependencies ...] in :pom-data would
  ;; duplicate the basis's and Clojars rejects the unparseable pom)
  (b/write-pom
   {:class-dir mcp-class-dir
    :lib mcp-lib
    :version mcp-version
    :basis (b/create-basis {:project "deps.edn" :aliases [:mcp]
                            :extra {:deps {'io.github.anusornc/kala {:mvn/version version}}}})
    :src-dirs ["mcp"]
    :pom-data (licenses-pom-data)})
  (b/copy-dir {:src-dirs ["mcp"] :target-dir mcp-class-dir})
  (b/copy-file {:src "LICENSE" :target (str mcp-class-dir "/LICENSE")})
  (b/jar {:class-dir mcp-class-dir :jar-file mcp-jar-file})
  (println "Built" mcp-jar-file))

(defn mcp-deploy [_]
  (mcp-jar nil)
  (let [pom (str mcp-class-dir "/META-INF/maven/" (namespace mcp-lib) "/" (name mcp-lib) "/pom.xml")]
    (println "Deploying" mcp-jar-file "to Clojars as" mcp-lib mcp-version)
    ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
     {:artifact mcp-jar-file
      :installer :remote
      :pom-file pom
      :repository {"clojars" {:url "https://clojars.org/repo"
                              :username (System/getenv "CLOJARS_USERNAME")
                              :password (System/getenv "CLOJARS_PASSWORD")}}})))

(defn mcp-uberjar
  "One executable jar for the MCP server: `java -jar kala-mcp-<v>-standalone.jar
  mcp.edn` — no Clojure CLI, no project checkout. Built from the local
  sources (the same tree the :mcp alias runs), so it always matches the
  checkout rather than a published release."
  [_]
  (b/delete {:path mcp-class-dir})
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:mcp]})]
    (b/copy-dir {:src-dirs ["src" "mcp"] :target-dir mcp-class-dir})
    (b/copy-file {:src "LICENSE" :target (str mcp-class-dir "/LICENSE")})
    (b/compile-clj {:basis basis :class-dir mcp-class-dir :src-dirs ["src" "mcp"]})
    (b/uber {:class-dir mcp-class-dir :uber-file mcp-uber-file :main 'kala.mcp :basis basis}))
  (println "Built" mcp-uber-file))

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
