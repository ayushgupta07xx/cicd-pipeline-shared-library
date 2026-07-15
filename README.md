# cicd-pipeline-shared-library

Reusable Jenkins shared library (Groovy) implementing a build-and-deploy pipeline
for containerised services targeting Kubernetes.

Onboarding a new repository requires a `Jenkinsfile` and a `deploy-config.yaml`
— no pipeline logic is duplicated per project.
