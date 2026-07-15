package io.finacplus.cicd

/**
 * Parses and validates a repository's deploy-config.yaml.
 *
 * This class is the contract between an application repository and the
 * delivery pipeline: the repository declares WHAT it is and WHERE it goes,
 * the library decides HOW. Adding a new repository or a new target cluster
 * therefore requires no change to pipeline code.
 *
 * Validation is fail-fast and explicit: a malformed config aborts the build
 * with a precise message rather than failing later inside kubectl.
 */
class DeployConfig implements Serializable {
    private static final long serialVersionUID = 1L

    private final def script
    final Map raw

    DeployConfig(def script, Map raw) {
        this.script = script
        this.raw = raw
    }

    /** Loads and validates the config from the workspace. */
    static DeployConfig load(def script, String path = 'deploy-config.yaml') {
        if (!script.fileExists(path)) {
            script.error("deploy-config.yaml not found at '${path}'. " +
                         "Every repository onboarded to this pipeline must declare one.")
        }
        Map raw = script.readYaml(file: path)
        DeployConfig cfg = new DeployConfig(script, raw)
        cfg.validate()
        return cfg
    }

    /** Fail-fast schema validation with actionable messages. */
    void validate() {
        List<String> errors = []

        if (raw?.schemaVersion != 1) {
            errors << "schemaVersion must be 1 (found: ${raw?.schemaVersion})"
        }
        ['name', 'version', 'imageRepo', 'port'].each { k ->
            if (!raw?.app?."${k}") { errors << "app.${k} is required" }
        }
        if (!raw?.registry?.host) { errors << "registry.host is required" }
        if (raw?.test?.commands && !raw?.test?.image) {
            errors << "test.image is required when test.commands is set — " +
                      "the pipeline does not assume a language runtime"
        }
        if (!raw?.manifests?.path) { errors << "manifests.path is required" }
        if (!(raw?.manifests?.files instanceof List) || raw.manifests.files.isEmpty()) {
            errors << "manifests.files must be a non-empty list"
        }
        if (!(raw?.environments instanceof List) || raw.environments.isEmpty()) {
            errors << "environments must be a non-empty list"
        } else {
            raw.environments.eachWithIndex { Map env, int i ->
                ['name', 'cluster', 'namespace', 'credentialId'].each { k ->
                    if (!env?."${k}") { errors << "environments[${i}].${k} is required" }
                }
                if (!(env?.branches instanceof List)) {
                    errors << "environments[${i}].branches must be a list (use [] to disable)"
                }
                if (env?.replicas != null && !(env.replicas instanceof Integer)) {
                    errors << "environments[${i}].replicas must be an integer"
                }
            }
            List names = raw.environments.collect { it?.name }
            if (names.size() != names.unique().size()) {
                errors << "environment names must be unique (found: ${names})"
            }
        }

        if (errors) {
            script.error("Invalid deploy-config.yaml:\n  - " + errors.join("\n  - "))
        }
    }

    String getAppName()    { raw.app.name }
    String getAppVersion() { raw.app.version as String }
    Integer getAppPort()   { raw.app.port as Integer }
    String getImageRepo()  { raw.app.imageRepo }
    String getRegistry()   { raw.registry.host }
    String getBuildContext()    { raw.build?.context ?: '.' }
    String getDockerfile()      { raw.build?.dockerfile ?: 'Dockerfile' }
    List<String> getTestCommands() { (raw.test?.commands ?: []) as List<String> }
    /** Container image the test commands execute in — declared per repository,
     *  because the library makes no assumption about language or toolchain. */
    String getTestImage() { raw.test?.image ?: 'alpine:3.21' }
    /** Optional dependency-installation command run before each test command. */
    String getTestSetup() { raw.test?.setup ?: '' }
    String getManifestPath()    { raw.manifests.path }
    List<String> getManifestFiles() { raw.manifests.files as List<String> }

    boolean isTrivyEnabled()      { raw.security?.trivy?.enabled as boolean }
    String  getTrivySeverity()    { raw.security?.trivy?.severity ?: 'HIGH,CRITICAL' }
    boolean isTrivyIgnoreUnfixed(){ raw.security?.trivy?.ignoreUnfixed as boolean }
    boolean isTrivyBlocking()     { raw.security?.trivy?.failOnFindings as boolean }

    List<Map> getEnvironments() { raw.environments as List<Map> }

    /** Environments this branch is permitted to deploy to, in declared order. */
    List<Map> environmentsForBranch(String branch) {
        return environments.findAll { Map env ->
            (env.branches ?: []).contains(branch)
        }
    }

    /**
     * Fully-qualified, immutable image reference.
     * Tag encodes branch, build number and commit so any running pod is
     * traceable to the exact source that produced it.
     */
    String imageRef(String branch, String buildNumber, String shortSha) {
        String safeBranch = branch.replaceAll(/[^A-Za-z0-9._-]/, '-').toLowerCase()
        return "${registry}/${imageRepo}:${safeBranch}-${buildNumber}-${shortSha}"
    }
}
