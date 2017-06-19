#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        string(name: 'AWS_REGION',
               defaultValue: 'us-west-2',
               description: 'AWS region to use for AMIs and testing'),
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: '1bb768fc-940d-4a95-95d0-27c1153e7fa0',
         description: 'AWS credentials list for AMI creation and releasing',
         name: 'AWS_RELEASE_CREDS',
         required: true],
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl',
         defaultValue: '6d37d17c-503e-4596-9a9b-1ab4373955a9',
         description: 'Credentials with permissions required by "kola run --platform=aws"',
         name: 'AWS_TEST_CREDS',
         required: true],
        choice(name: 'BOARD',
               choices: "amd64-usr\narm64-usr",
               description: 'Target board to build'),
        string(name: 'GROUP',
               defaultValue: 'developer',
               description: 'Which release group owns this build'),
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey',
         defaultValue: '',
         description: 'Credential ID for SSH Git clone URLs',
         name: 'BUILDS_CLONE_CREDS',
         required: false],
        choice(name: 'COREOS_OFFICIAL',
               choices: "0\n1"),
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
         description: '''Credentials ID for a JSON file passed as the \
GOOGLE_APPLICATION_CREDENTIALS value for uploading development files to the \
Google Storage URL, requires write permission''',
         name: 'GS_DEVEL_CREDS',
         required: true],
        string(name: 'GS_DEVEL_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where development files are uploaded'),
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
         description: '''Credentials ID for a JSON file passed as the \
GOOGLE_APPLICATION_CREDENTIALS value for uploading release files to the \
Google Storage URL, requires write permission''',
         name: 'GS_RELEASE_CREDS',
         required: true],
        string(name: 'GS_RELEASE_DOWNLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where release files are downloaded'),
        string(name: 'GS_RELEASE_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where release files are uploaded'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        string(name: 'MANIFEST_TAG',
               defaultValue: ''),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        [$class: 'CredentialsParameterDefinition',
         credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
         defaultValue: 'buildbot-official.EF4B4ED9.subkey.gpg',
         description: 'Credential ID for a GPG private key file',
         name: 'SIGNING_CREDS',
         required: true],
        string(name: 'SIGNING_USER',
               defaultValue: 'buildbot@coreos.com',
               description: 'E-mail address to identify the GPG key'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

/* The unsigned image generated here is still a dev file with Secure Boot.  */
def UPLOAD_CREDS = params.GS_RELEASE_CREDS
def UPLOAD_ROOT = params.GS_RELEASE_ROOT
if (false && params.COREOS_OFFICIAL == '1') {
    UPLOAD_CREDS = params.GS_DEVEL_CREDS
    UPLOAD_ROOT = params.GS_DEVEL_ROOT
}

node('coreos && amd64 && sudo') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        writeFile file: 'verify.asc', text: params.VERIFY_KEYRING ?: ''

        sshagent(credentials: [params.BUILDS_CLONE_CREDS],
                 ignoreMissing: true) {
            withCredentials([
                [$class: 'FileBinding',
                 credentialsId: params.SIGNING_CREDS,
                 variable: 'GPG_SECRET_KEY_FILE'],
                [$class: 'FileBinding',
                 credentialsId: params.GS_DEVEL_CREDS,
                 variable: 'GS_DEVEL_CREDS'],
                [$class: 'FileBinding',
                 credentialsId: UPLOAD_CREDS,
                 variable: 'GOOGLE_APPLICATION_CREDENTIALS']
            ]) {
                withEnv(["BOARD=${params.BOARD}",
                         "COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                         "DOWNLOAD_ROOT=${params.GS_DEVEL_ROOT}",
                         "GROUP=${params.GROUP}",
                         "MANIFEST_NAME=${params.MANIFEST_NAME}",
                         "MANIFEST_TAG=${params.MANIFEST_TAG}",
                         "MANIFEST_URL=${params.MANIFEST_URL}",
                         "SIGNING_USER=${params.SIGNING_USER}",
                         "UPLOAD_ROOT=${UPLOAD_ROOT}"]) {
                    sh '''#!/bin/bash -ex

# build may not be started without a ref value
[[ -n "${MANIFEST_TAG}" ]]

# set up GPG for verifying tags
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap "rm -rf '${GNUPGHOME}'" EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

./bin/cork update --create --downgrade-replace --verify --verify-signature --verbose \
                  --manifest-url "${MANIFEST_URL}" \
                  --manifest-branch "refs/tags/${MANIFEST_TAG}" \
                  --manifest-name "${MANIFEST_NAME}"

# first thing, clear out old images
sudo rm -rf chroot/build src/build torcx

enter() {
  sudo ln -f "${GS_DEVEL_CREDS}" chroot/etc/portage/gangue.json
  [ -s verify.asc ] &&
  sudo ln -f verify.asc chroot/etc/portage/gangue.asc &&
  verify_key=--verify-key=/etc/portage/gangue.asc || verify_key=
  trap 'sudo rm -f chroot/etc/portage/gangue.*' RETURN
  ./bin/cork enter --experimental -- env \
    COREOS_DEV_BUILDS="${DOWNLOAD_ROOT}" \
    PORTAGE_SSH_OPTS= \
    {FETCH,RESUME}COMMAND_GS="/usr/bin/gangue get \
--json-key=/etc/portage/gangue.json $verify_key \
"'"${URI}" "${DISTDIR}/${FILE}"' \
    "$@"
}

script() {
  local script="/mnt/host/source/src/scripts/${1}"; shift
  enter "${script}" "$@"
}

sudo cp bin/gangue chroot/usr/bin/gangue  # XXX: until SDK mantle has it

source .repo/manifests/version.txt
export COREOS_BUILD_ID

# Set up GPG for signing images
gpg --import "${GPG_SECRET_KEY_FILE}"

script setup_board \
    --board=${BOARD} \
    --getbinpkgver="${COREOS_VERSION}" \
    --regen_configs_only

if [[ "${COREOS_OFFICIAL}" -eq 1 ]]; then
  script set_official --board=${BOARD} --official
else
  script set_official --board=${BOARD} --noofficial
fi

# Try to find the version's  torcx store, but don't require it
torcx_store=
enter gsutil cp -r \
    "${DOWNLOAD_ROOT}/boards/${BOARD}/${COREOS_VERSION}/torcx" \
    /mnt/host/source/ &&
torcx_store=/mnt/host/source/torcx &&
for image in torcx/*.torcx.tgz
do
        gpg --verify "${image}.sig"
done

# Work around the lack of symlink support in GCS
shopt -s nullglob
for default in torcx/*:com.coreos.cl.torcx.tgz
do
        for image in torcx/*.torcx.tgz
        do
                [ "x${default}" != "x${image}" ] &&
                cmp --silent -- "${default}" "${image}" &&
                ln -fns "${image##*/}" "${default}"
        done
done

script build_image \
    --board=${BOARD} \
    --group=${GROUP} \
    --getbinpkg \
    --getbinpkgver="${COREOS_VERSION}" \
    --sign="${SIGNING_USER}" \
    --sign_digests="${SIGNING_USER}" \
    ${torcx_store:+--torcx_store="${torcx_store}"} \
    --upload_root="${UPLOAD_ROOT}" \
    --upload prod container
'''  /* Editor quote safety: ' */
                }
            }
        }
    }

    stage('Post-build') {
        fingerprint "chroot/build/${params.BOARD}/var/lib/portage/pkgs/*/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2,src/build/images/${params.BOARD}/latest/*"
        dir('src/build') {
            deleteDir()
        }
    }
}

stage('Downstream') {
    parallel failFast: false,
        'board-vm-matrix': {
            if (false && params.COREOS_OFFICIAL == '1')
                build job: 'sign-image', parameters: [
                    string(name: 'AWS_REGION', value: params.AWS_REGION),
                    [$class: 'CredentialsParameterValue', name: 'AWS_RELEASE_CREDS', value: params.AWS_RELEASE_CREDS],
                    [$class: 'CredentialsParameterValue', name: 'AWS_TEST_CREDS', value: params.AWS_TEST_CREDS],
                    string(name: 'BOARD', value: params.BOARD),
                    [$class: 'CredentialsParameterValue', name: 'BUILDS_CLONE_CREDS', value: params.BUILDS_CLONE_CREDS],
                    string(name: 'GROUP', value: params.GROUP),
                    [$class: 'CredentialsParameterValue', name: 'GS_DEVEL_CREDS', value: params.GS_DEVEL_CREDS],
                    string(name: 'GS_DEVEL_ROOT', value: params.GS_DEVEL_ROOT),
                    [$class: 'CredentialsParameterValue', name: 'GS_RELEASE_CREDS', value: params.GS_RELEASE_CREDS],
                    string(name: 'GS_RELEASE_DOWNLOAD_ROOT', value: params.GS_RELEASE_DOWNLOAD_ROOT),
                    string(name: 'GS_RELEASE_ROOT', value: params.GS_RELEASE_ROOT),
                    string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                    string(name: 'MANIFEST_TAG', value: params.MANIFEST_TAG),
                    string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                    [$class: 'CredentialsParameterValue', name: 'SIGNING_CREDS', value: params.SIGNING_CREDS],
                    string(name: 'SIGNING_USER', value: params.SIGNING_USER),
                    text(name: 'VERIFY_KEYRING', value: params.VERIFY_KEYRING),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
            else
                build job: 'vm-matrix', parameters: [
                    string(name: 'AWS_REGION', value: params.AWS_REGION),
                    [$class: 'CredentialsParameterValue', name: 'AWS_RELEASE_CREDS', value: params.AWS_RELEASE_CREDS],
                    [$class: 'CredentialsParameterValue', name: 'AWS_TEST_CREDS', value: params.AWS_TEST_CREDS],
                    string(name: 'BOARD', value: params.BOARD),
                    [$class: 'CredentialsParameterValue', name: 'BUILDS_CLONE_CREDS', value: params.BUILDS_CLONE_CREDS],
                    string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
                    string(name: 'GROUP', value: params.GROUP),
                    [$class: 'CredentialsParameterValue', name: 'GS_DEVEL_CREDS', value: params.GS_DEVEL_CREDS],
                    string(name: 'GS_DEVEL_ROOT', value: params.GS_DEVEL_ROOT),
                    [$class: 'CredentialsParameterValue', name: 'GS_RELEASE_CREDS', value: params.GS_RELEASE_CREDS],
                    string(name: 'GS_RELEASE_DOWNLOAD_ROOT', value: params.GS_RELEASE_DOWNLOAD_ROOT),
                    string(name: 'GS_RELEASE_ROOT', value: params.GS_RELEASE_ROOT),
                    string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                    string(name: 'MANIFEST_TAG', value: params.MANIFEST_TAG),
                    string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                    [$class: 'CredentialsParameterValue', name: 'SIGNING_CREDS', value: params.SIGNING_CREDS],
                    string(name: 'SIGNING_USER', value: params.SIGNING_USER),
                    text(name: 'VERIFY_KEYRING', value: params.VERIFY_KEYRING),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
        },
        'kola-qemu': {
            if (params.BOARD == 'amd64-usr')
                build job: '../kola/qemu', parameters: [
                    [$class: 'CredentialsParameterValue', name: 'BUILDS_CLONE_CREDS', value: params.BUILDS_CLONE_CREDS],
                    [$class: 'CredentialsParameterValue', name: 'DOWNLOAD_CREDS', value: UPLOAD_CREDS],
                    string(name: 'DOWNLOAD_ROOT', value: UPLOAD_ROOT),
                    string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                    string(name: 'MANIFEST_TAG', value: params.MANIFEST_TAG),
                    string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                    text(name: 'VERIFY_KEYRING', value: params.VERIFY_KEYRING),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
        }
}
