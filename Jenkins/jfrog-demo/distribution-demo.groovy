node {
    def jfrogInstance = JFrog.instance 'arti-liwei'
    def dsServer = jfrogInstance.distribution
    def releaseBundleSpec = """{
      "files": [
        {
          "aql": "items.find({\"$and\": [{\"property.key\": {\"$eq\": \"JiraID\"}},{\"property.value\": {\"$eq\": \"851567\"}}]})"
        }
      ]
    }"""
    def distributionRules = """{
      "distribution_rules": [
        {
          "site_name": "Edge",
          "city_name": "Edge",
          "country_codes": ["Edge"]
        }
      ]
    }"""

    stage ('Clone') {
        git url: 'https://github.com/jfrog/project-examples.git'
    }

    stage ("Upload file") {
        def uploadSpec = """{
            "files": [
                {
                    "pattern": "jenkins-examples/pipeline-examples/resources/ArtifactoryPipeline.zip",
                    "target": "libs-release-local"
                }
            ]
        }"""
        rtServer.upload spec: uploadSpec
    }

    stage ("Create release bundle") {
        dsServer.createReleaseBundle name: "${JOB_NAME}", version: "v_1.${BUILD_NUMBER}", spec: releaseBundleSpec, signImmediately: true,gpgPassphrase: "password",dryRun: true
    }


    stage ("Distribute release bundle") {
        dsServer.distributeReleaseBundle name: "${JOB_NAME}", version: "v_1.${BUILD_NUMBER}", distRules: distributionRules, sync: true
    }


}