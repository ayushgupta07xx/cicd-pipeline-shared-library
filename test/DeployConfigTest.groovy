package io.finacplus.cicd

/**
 * Unit tests for DeployConfig.
 *
 * DeployConfig receives the CpsScript object by constructor rather than
 * reaching for it globally, so it can be tested with a stub and no Jenkins at
 * all. That testability is precisely why the class lives in src/ rather than
 * being inlined into the pipeline definition in vars/.
 *
 * Deliberately framework-free: Groovy 4 moved GroovyTestCase into a separate
 * module, and a shared library should not acquire a build system to assert on
 * a config parser. Plain assertions run anywhere `groovy` does.
 *
 * Run:  ./test/run-tests.sh
 */

/** Minimal stand-in for the CpsScript object the library receives. */
class ScriptStub {
    Map yaml
    boolean exists = true
    List<String> errors = []

    boolean fileExists(String p) { exists }
    Map readYaml(Map args)       { yaml }
    void error(String msg)       { errors << msg; throw new RuntimeException(msg) }
}

Map validConfig() {
    [
        schemaVersion: 1,
        app: [name: 'svc', version: '1.0.0', imageRepo: 'org/svc', port: 8080],
        registry: [host: 'localhost:5001'],
        test: [image: 'python:3.12-slim', commands: ['pytest -q']],
        manifests: [path: 'k8s', files: ['deployment.yaml']],
        environments: [
            [name: 'staging', cluster: 'kind-staging', namespace: 'demo',
             credentialId: 'kubeconfig-staging', branches: ['main'], autoDeploy: true],
            [name: 'prod', cluster: 'kind-prod', namespace: 'demo',
             credentialId: 'kubeconfig-prod', branches: ['main'], autoDeploy: false]
        ]
    ]
}

int passed = 0, failed = 0
def check = { String name, Closure body ->
    try { body(); passed++; println "  PASS  ${name}" }
    catch (Throwable t) { failed++; println "  FAIL  ${name}\n          ${t.message}" }
}
def expectError = { ScriptStub s, String fragment ->
    assert s.errors, "expected an error, none raised"
    assert s.errors[0].contains(fragment), "expected '${fragment}' in: ${s.errors[0]}"
}

println "DeployConfig"

check('valid config passes') {
    def s = new ScriptStub(yaml: validConfig())
    def cfg = DeployConfig.load(s)
    assert cfg.appName == 'svc'
    assert cfg.appPort == 8080
    assert cfg.environments.size() == 2
    assert s.errors.isEmpty()
}

check('missing file is rejected') {
    def s = new ScriptStub(yaml: validConfig(), exists: false)
    try { DeployConfig.load(s) } catch (ignored) {}
    expectError(s, 'deploy-config.yaml not found')
}

check('wrong schemaVersion is rejected') {
    def c = validConfig(); c.schemaVersion = 2
    def s = new ScriptStub(yaml: c)
    try { DeployConfig.load(s) } catch (ignored) {}
    expectError(s, 'schemaVersion must be 1')
}

check('missing app.name is rejected') {
    def c = validConfig(); c.app.remove('name')
    def s = new ScriptStub(yaml: c)
    try { DeployConfig.load(s) } catch (ignored) {}
    expectError(s, 'app.name is required')
}

check('test.image required when test.commands present') {
    // Regression: the library once hardcoded a Python image, so a Node service
    // silently ran npm inside python:3.12-slim. The runtime is now part of each
    // repository's contract and its absence is a configuration error.
    def c = validConfig(); c.test.remove('image')
    def s = new ScriptStub(yaml: c)
    try { DeployConfig.load(s) } catch (ignored) {}
    expectError(s, 'test.image is required')
}

check('duplicate environment names rejected') {
    def c = validConfig(); c.environments[1].name = 'staging'
    def s = new ScriptStub(yaml: c)
    try { DeployConfig.load(s) } catch (ignored) {}
    expectError(s, 'environment names must be unique')
}

check('all errors reported together, not one at a time') {
    def c = validConfig(); c.app.remove('name'); c.registry.remove('host')
    def s = new ScriptStub(yaml: c)
    try { DeployConfig.load(s) } catch (ignored) {}
    expectError(s, 'app.name is required')
    expectError(s, 'registry.host is required')
}

check('environmentsForBranch filters by declared policy') {
    def c = validConfig()
    c.environments[0].branches = ['main', 'release/*']
    def cfg = DeployConfig.load(new ScriptStub(yaml: c))
    assert cfg.environmentsForBranch('main')*.name == ['staging', 'prod']
    assert cfg.environmentsForBranch('feature/x').isEmpty()
}

check('feature branch targets nothing (validation P2)') {
    def cfg = DeployConfig.load(new ScriptStub(yaml: validConfig()))
    assert cfg.environmentsForBranch('feature/experiment').isEmpty()
}

check('imageRef is immutable and traceable to source') {
    def cfg = DeployConfig.load(new ScriptStub(yaml: validConfig()))
    assert cfg.imageRef('main', '42', 'abc1234') == 'localhost:5001/org/svc:main-42-abc1234'
}

check('imageRef sanitises branch names into valid docker tags') {
    // A slash in a branch name yields an invalid tag and fails at push time.
    def cfg = DeployConfig.load(new ScriptStub(yaml: validConfig()))
    assert cfg.imageRef('feature/branch-filter-demo', '1', 'd4921a9') ==
           'localhost:5001/org/svc:feature-branch-filter-demo-1-d4921a9'
    assert cfg.imageRef('Release/V2.0', '7', 'aaa1111') ==
           'localhost:5001/org/svc:release-v2.0-7-aaa1111'
}

check('trivy defaults are conservative') {
    def c = validConfig(); c.remove('security')
    def cfg = DeployConfig.load(new ScriptStub(yaml: c))
    assert cfg.trivySeverity == 'HIGH,CRITICAL'
    assert !cfg.trivyBlocking
}

println "\n${passed} passed, ${failed} failed"
System.exit(failed > 0 ? 1 : 0)
