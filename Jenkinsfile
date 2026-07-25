// =============================================================================
//  Jenkins pipeline — build, test and DEPLOY the audit ingestion microservice
//  to Elastic Beanstalk (Docker platform), env `bankstmt-audit-prod`.
//
//  Flow: Preflight -> Unit tests -> Build fat jar -> Deploy to EB.
//  The deploy stage runs only on `develop`/`main` (feature branches build+test
//  but never deploy). Mirrors the bankstmt-web EB deploy pattern.
//
//  The EB source bundle is a SLIM runtime bundle assembled here (a runtime-only
//  Dockerfile + the prebuilt app.jar + .ebextensions) so the small t3 instance
//  never runs a Gradle/JDK build itself — the jar is built on the Jenkins agent.
//
//  SECRETS: none in Jenkins. AWS access comes from the agent's IAM role or the
//  jenkins user's AWS profile, which must allow: s3:PutObject on the EB versions
//  bucket + elasticbeanstalk create-application-version/update-environment/describe
//  on application `bankstmt-audit`. The audit DB creds + queue URL are EB env
//  properties on the environment already — the pipeline never touches them.
//
//  Agent prerequisites: JDK 25, aws CLI v2, zip, git.
//  Trigger: GitHub webhook / SCM poll -> this job on push to develop or main.
// =============================================================================

pipeline {
  agent any

  options {
    disableConcurrentBuilds()
    timestamps()
    timeout(time: 30, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '10'))
  }

  environment {
    AWS_REGION  = 'us-east-1'
    EB_APP      = 'bankstmt-audit'
    EB_ENV      = 'bankstmt-audit-prod'
    GRADLE_OPTS = '-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx2g'
  }

  stages {

    stage('Preflight') {
      steps {
        sh '''
          set -euo pipefail
          command -v java >/dev/null || { echo "java missing — JDK 25 required"; exit 1; }
          java -version 2>&1 | head -1
          command -v aws  >/dev/null || { echo "aws CLI v2 missing"; exit 1; }
          command -v zip  >/dev/null || { echo "zip missing — apt-get install zip"; exit 1; }
          test -x ./gradlew || chmod +x ./gradlew
        '''
      }
    }

    stage('Unit tests') {
      steps {
        // Gradle fails the build on a failing test; the JUnit report still publishes (post).
        sh './gradlew test --no-daemon --stacktrace'
      }
    }

    stage('Build jar') {
      steps {
        sh './gradlew bootJar --no-daemon --stacktrace'
      }
    }

    stage('Deploy to Elastic Beanstalk') {
      // In the multibranch `branches` job, deploy only from develop/main (feature branches
      // build+test but never deploy). In the standalone `deploy` job (BRANCH_NAME unset, run
      // by the release gates or manually), the deploy is the whole point — always run it.
      when { anyOf { branch 'develop'; branch 'main'; expression { !env.BRANCH_NAME } } }
      steps {
        sh '''
          set -euo pipefail
          SHORT=$(echo "${GIT_COMMIT:-local}" | cut -c1-12)
          LABEL="audit-${BUILD_NUMBER}-${SHORT}"

          # ---- assemble the slim EB Docker bundle: runtime Dockerfile + jar + .ebextensions ----
          rm -rf ebbundle deploy.zip
          mkdir -p ebbundle/.ebextensions
          JAR=$(ls build/libs/*.jar | grep -v -- '-plain.jar' | head -1)
          [ -n "$JAR" ] || { echo "no boot jar found in build/libs"; exit 1; }
          cp "$JAR" ebbundle/app.jar
          cp .ebextensions/*.config ebbundle/.ebextensions/ 2>/dev/null || true
          cat > ebbundle/Dockerfile <<'DOCKER'
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
DOCKER
          (cd ebbundle && zip -qr ../deploy.zip .)
          unzip -l deploy.zip | tail -3

          # ---- publish the version + roll the environment (mirrors bankstmt-web) ----
          BUCKET=$(aws elasticbeanstalk create-storage-location --query S3Bucket --output text)
          aws s3 cp deploy.zip "s3://$BUCKET/$EB_APP/$LABEL.zip"
          aws elasticbeanstalk create-application-version \
            --application-name "$EB_APP" --version-label "$LABEL" \
            --source-bundle S3Bucket="$BUCKET",S3Key="$EB_APP/$LABEL.zip" \
            --description "Jenkins build ${BUILD_NUMBER} (${SHORT})"

          # EB version creation is eventually consistent — wait until the version is visible
          # so the update below does not race it ("No Application Version named ... found").
          n=0
          until [ "$(aws elasticbeanstalk describe-application-versions \
                --application-name "$EB_APP" --version-labels "$LABEL" \
                --query 'length(ApplicationVersions)' --output text)" = "1" ]; do
            n=$((n+1)); [ "$n" -gt 30 ] && { echo "application version never became visible"; exit 1; }
            sleep 2
          done

          # Guard: the environment must belong to EB_APP, else update-environment can't see the version.
          ENV_APP=$(aws elasticbeanstalk describe-environments --environment-names "$EB_ENV" \
            --query 'Environments[0].ApplicationName' --output text)
          if [ "$ENV_APP" != "$EB_APP" ]; then
            echo "MISCONFIGURATION: env $EB_ENV belongs to application '$ENV_APP', not '$EB_APP'."; exit 1
          fi

          # Retry the update — it can bounce if the environment is mid-operation.
          n=0
          until aws elasticbeanstalk update-environment \
                --application-name "$EB_APP" --environment-name "$EB_ENV" --version-label "$LABEL"; do
            n=$((n+1)); [ "$n" -gt 5 ] && { echo "update-environment kept failing"; exit 1; }
            echo "update-environment not accepted yet — retry $n/5 in 10s..."; sleep 10
          done

          echo "Waiting for the environment to finish updating..."
          aws elasticbeanstalk wait environment-updated --environment-names "$EB_ENV"

          HEALTH=$(aws elasticbeanstalk describe-environments --environment-names "$EB_ENV" \
            --query 'Environments[0].Health' --output text)
          VERSION=$(aws elasticbeanstalk describe-environments --environment-names "$EB_ENV" \
            --query 'Environments[0].VersionLabel' --output text)
          echo "Environment health: $HEALTH, running version: $VERSION"
          [ "$HEALTH" = "Green" ] && [ "$VERSION" = "$LABEL" ] \
            || { echo "EB deploy unhealthy or wrong version"; exit 1; }

          # Application-level gate: the actuator health endpoint must report UP.
          CNAME=$(aws elasticbeanstalk describe-environments --environment-names "$EB_ENV" \
            --query 'Environments[0].CNAME' --output text)
          n=0
          until curl -fsS -m 8 "http://$CNAME/actuator/health" | grep -q '"status":"UP"'; do
            n=$((n+1)); [ "$n" -gt 12 ] && { echo "actuator health never reported UP"; exit 1; }
            echo "waiting for /actuator/health UP ($n/12)..."; sleep 5
          done
          echo "Audit service healthy on version $LABEL."
        '''
      }
    }
  }

  post {
    always {
      junit testResults: '**/build/test-results/test/*.xml', allowEmptyResults: false
      sh 'rm -rf ebbundle deploy.zip || true'
    }
    success { echo 'Audit service: tests green' + (env.BRANCH_NAME in ['develop', 'main'] ? ' and deployed to Elastic Beanstalk.' : ' (build-only, non-deploy branch).') }
    failure { echo 'Audit pipeline FAILED — EB keeps running the previous application version.' }
  }
}
