# Jenkins Pipeline Example

This example allows you to run ORT in a [Jenkins](https://www.jenkins.io/) [pipeline](https://www.jenkins.io/doc/book/pipeline/).

## Getting Started

Please follow the regular [installation instructions](https://www.jenkins.io/doc/book/installing/) and additionally ensure to have the following Jenkins plugins installed:

* [Copy Artifact](https://plugins.jenkins.io/copyartifact/)
* [Docker Pipeline](https://plugins.jenkins.io/docker-workflow)
* [File Parameter](https://plugins.jenkins.io/file-parameters/)
* [Pipeline Utility Steps](https://plugins.jenkins.io/pipeline-utility-steps)

Also ensure that [Docker is installed](https://docs.docker.com/engine/install/) on your system with [BuildKit enabled](https://docs.docker.com/develop/develop-images/build_enhancements/#to-enable-buildkit-builds), and that Jenkins is allowed to run Docker (you might need to run `usermod -aG docker jenkins` to add the `jenkins` user to the `docker` group).

If you are running Jenkins as a Docker image as opposed to using the Java WAR, you need to [install Docker inside the Jenkins container](https://docs.docker.com/engine/install/debian/).

Next, on the *Dashboard* click *New Item*, enter e.g. "ORT" as the name, select *Pipeline* and then click *OK*.
In the *Pipeline* section of the job configuration, choose "Pipeline script from SCM" and the following settings:

* *SCM*: `Git`
* *Repository URL*: `https://github.com/oss-review-toolkit/ort.git`
* *Branch Specifier*: `*/main`
* *Script Path*: `integrations/jenkins/Jenkinsfile`

Finally, click on *Save*.

## Running the Pipeline

You are now good to go to perform an initial run of the pipeline by clicking on *Build Now*.
This will not yet run ORT, but just configure the pipeline parameters and its stages.
Note that this first run is expected to "fail".

After this run, there will be a new *Build with Parameters* entry in the job menu.
Clicking on it will show the job parameter form.
For a start, leave all the defaults and click on *Build* at the bottom.
This first run will take quite a long time in the "Build ORT Docker image" stage as the Docker image is built from scratch.
However, unless changes to the `Dockerfile` are done, subsequent runs will be *much* faster.
Also, the "Run ORT scanner" stage will take a bit longer, but again subsequent scans will be faster thanks to the use of stored scan results.

Once the pipeline run completes you will see that the "Run ORT evaluator" stage is actually yellow and marked as "unstable".
This is expected due to the use of a built-in rules resource as an example.
The ORT results from the stages and the report formats are published as build artifacts.
All in all, a pipeline run for scanning the [Semver4j](https://github.com/vdurmont/semver4j) as an example project should look similar to this:

![ORT Pipeline Stage View](pipeline.png)

Now the pipeline is properly configured / initialized, and you can click again on *Build with Parameters* to run it against VCS repositories Jenkins has (read-)access to.
Of course, you can also programmatically trigger the ORT pipeline from your application build pipelines.
