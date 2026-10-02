(ns deploy
  "Publish the built jar to Clojars. Credentials come from the
  CLOJARS_USERNAME and CLOJARS_PASSWORD environment variables (wired to
  repository secrets by the release workflow)."
  (:require [slipset.deps-deploy :as deps-deploy]))

(def ^:private lib 'io.github.anusornc/datomic-opensource)

(defn -main [& _]
  (let [version (or (System/getenv "RELEASE_VERSION")
                    (throw (ex-info "RELEASE_VERSION not set" {})))
        jar (format "target/%s-%s.jar" (name lib) version)
        pom (format "target/classes/META-INF/maven/%s/%s/pom.xml"
                    (namespace lib) (name lib))]
    (deps-deploy/deploy {:artifact jar :pom-file pom :repository "clojars"})
    (println "Deployed" (str lib) version "to Clojars")))
