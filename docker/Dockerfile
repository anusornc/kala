# The kala MCP server as one runnable container — the official MCP
# registry's "oci" package type (there is no Maven/Clojars registryType),
# wrapping the standalone jar. Build the jar first:
#   clojure -T:build mcp-uberjar
FROM eclipse-temurin:21-jre-alpine
# ownership verification: this label MUST equal server.json's name
LABEL io.modelcontextprotocol.server.name="io.github.anusornc/kala-mcp"
COPY target/kala-mcp-*-standalone.jar /opt/kala-mcp.jar
# both the config and the store ride mounts — persistence across restarts
# was tested with exactly this invocation:
#   docker run -i -v $PWD/mcp.edn:/data/mcp.edn -v $PWD/store:/data/store \
#     ghcr.io/anusornc/kala-mcp:0.5.0
ENTRYPOINT ["java", "-jar", "/opt/kala-mcp.jar", "/data/mcp.edn"]
