# Jenkins CI/CD for bankstmt-audit

All jobs for this repo are generated from [`jobs.groovy`](jobs.groovy) (Job DSL) by the
**seed** job — never hand-create jobs in the UI. Edit `jobs.groovy`, commit, re-run the seed.

## Pipelines

| Job | Jenkinsfile | Trigger | What it does |
|-----|-------------|---------|--------------|
| `branches` (multibranch) | `Jenkinsfile` | push to `develop`/`main` | build + unit test; **deploy to Elastic Beanstalk** (`bankstmt-audit-prod`) on develop/main |
| `unit` | `Jenkinsfile.unit` | SCM poll (H/5) | fast `./gradlew test` |
| `deploy` | `Jenkinsfile` | manual / release gate | build jar → EB bundle → `update-environment` → health gate |
| `release-develop` | `Jenkinsfile.release-develop` | manual (pinned to `develop`) | tests must pass → trigger `deploy` |
| `release-main` | `Jenkinsfile.release-main` | manual (pinned to `main`) | tests must pass → trigger `deploy` |

## Deploy model

The deploy pipeline builds the fat jar on the Jenkins agent, then ships a **slim EB bundle**
(runtime-only `Dockerfile` + `app.jar` + `.ebextensions`) to the `bankstmt-audit` EB application
and rolls `bankstmt-audit-prod`. The small EC2 instance never runs a Gradle/JDK build. It gates
on EB health `Green` + the exact version label + `/actuator/health` returning `UP`.

## One-time bootstrap

1. **Job DSL** plugin installed.
2. Create ONE pipeline by hand: *New Item → Pipeline → "Pipeline script from SCM" → this repo →
   Script Path = `Jenkinsfile.seed`*.
3. Run it (params: `SEED_BRANCH` blank = track the seed's branch; `SEED_CREDENTIALS_ID` = git
   credential id, blank if the agent key already has repo access). It generates every job above.

## Agent / AWS requirements

- Agent tools: **JDK 25**, **aws CLI v2**, **zip**, **git**.
- AWS identity (agent instance role or the `jenkins` user's profile) must allow, in `us-east-1`:
  - `s3:PutObject` on the Elastic Beanstalk versions bucket,
  - `elasticbeanstalk:CreateApplicationVersion` / `UpdateEnvironment` / `Describe*` on
    application `bankstmt-audit`.
- No secrets are stored in Jenkins. The audit DB credentials and SQS queue URL are Elastic
  Beanstalk environment properties on `bankstmt-audit-prod` — the pipeline never touches them.
