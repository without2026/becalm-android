Feature: BeCalm critical user journeys
  The app organizes work around people by ingesting communication sources,
  resolving participants, rebuilding the person graph, and generating optional
  person memory artifacts.

  Background:
    Given each scenario starts with isolated local app data
    And backend responses are controlled by fixtures or a deployed dev backend

  @happy @auth @onboarding @androidTest
  Scenario: E2E-001 New user signs in and reaches the person-centered home screen
    Given I launch BeCalm with no local session
    When I sign in with a valid account
    And I complete required privacy and onboarding steps
    Then I should land on the main screen
    And the first screen should show people-centered relationship history
    # Automation: androidTest navigation smoke; local coverage AuthUiTest and OnboardingUiTest; adb smoke verifies crash-free launch.

  @happy @sources @onboarding @settings @androidTest
  Scenario: E2E-002 Logged-in user configures sources from setup and sees the same state in Settings
    Given I am a logged-in user
    When I open source setup
    And I connect or skip each available source independently
    Then source connection state should be saved
    And Settings Sources should render the same source state
    # Automation: androidTest source/setup smoke; local coverage OnboardingUiTest, SourcesUiTest, SourcesLocalIntegrationTest.

  @happy @meeting @manual-import @worker
  Scenario: E2E-003 User manually imports a valid meeting transcript and it becomes person work context
    Given I am a logged-in user with meeting source enabled
    And I have a valid meeting transcript file
    When I add the transcript from the meeting source UI
    Then the file should be accepted by the meeting ingestion path
    And canonical source_event_participants should be saved
    And person_interactions should be rebuilt for resolved participants
    And ProfileMemoryWorker should be enqueued for affected people
    # Automation: adb file-picker smoke when device is available; local coverage MeetingTranscriptUploadWorkerSpecTest, AiPersonPipelineLocalIntegrationTest, PersonInteractionIndexWorkerLocalIntegrationTest.

  @happy @voice @recordings @worker
  Scenario: E2E-004 Supported call recording appears in Recordings and is processed automatically
    Given I am a logged-in user with voice source enabled
    And the configured Recordings folder is accessible
    When a supported audio file appears in the folder
    Then MediaStore scanning should create one raw ingestion event
    And upload/extraction work should be enqueued
    And returned source_event_participants should rebuild the person graph
    # Automation: adb MediaStore smoke when device is available; local coverage MediaStoreWorkerSpecTest, VoiceUploadWorkerNotificationSpecTest, AiPersonPipelineLocalIntegrationTest.

  @happy @person-matching @people @worker
  Scenario: E2E-005 User confirms an unresolved participant as an existing person
    Given a source event has an unresolved participant
    And a matching existing person is available
    When I open person matching review
    And I confirm the suggested person
    Then the participant should become resolved
    And PersonInteractionIndexWorker should rebuild the projection
    And the person detail timeline should include the source event
    # Automation: local coverage PersonManualMatchPipelineLocalIntegrationTest and PersonsScreenStateSourceLocalIntegrationTest; adb smoke verifies review route.

  @happy @person-memory @worker @backend
  Scenario: E2E-006 Person memory is generated and mirrored after graph changes
    Given a resolved person has source participants and interactions
    When PersonInteractionIndexWorker finishes rebuilding affected people
    Then WorkScheduler should enqueue ProfileMemoryWorker with a person-scoped unique key
    And memory.md should be written locally from structured evidence only
    And Railway upload should be attempted for person-memory storage
    # Automation: local coverage PersonInteractionIndexWorkerLocalIntegrationTest, ProfileMemoryWorkerLocalIntegrationTest, PersonMemoryRemoteRepositoryLocalIntegrationTest; backend coverage test_v1_api.py person_memory.

  @error @auth @settings @regression
  Scenario: E2E-007 User wipes local data and navigates to Sources without crashing
    Given I am signed in
    When I wipe local data and sign out
    And I navigate to Settings Sources
    Then the app should not access a user-scoped Room database before sign-in
    And I should see a signed-out or empty-safe state
    # Automation: local coverage SettingsUiTest and AuthUiTest; adb smoke watches AndroidRuntime and BeCalmDatabase logs.

  @error @person-memory @offline @worker
  Scenario: E2E-008 Person memory upload fails offline but local generation remains available
    Given person memory generation succeeds locally
    When Railway upload fails due to network or server error
    Then memory.md should remain written locally
    And the worker should report upload pending
    And source ingestion and person UI should continue working
    # Automation: local coverage ProfileMemoryWorkerLocalIntegrationTest and PersonMemoryRemoteRepositoryLocalIntegrationTest.

  @error @meeting @manual-import
  Scenario: E2E-009 User selects an unsupported meeting file type
    Given I am a logged-in user with meeting source enabled
    When I select an unsupported meeting file type
    Then the app should reject the file before ingestion
    And no raw ingestion event should be created
    # Automation: androidTest/manual adb file-picker smoke when device is available; local coverage pending in meeting import validation tests.

  @error @permissions @recordings
  Scenario: E2E-010 User denies media or recording folder permission
    Given I am a logged-in user
    When I deny required media or recording folder permission
    Then voice and meeting auto-detection should remain disabled or limited
    And other sources should remain usable
    # Automation: androidTest permission flow when device is available; local coverage OnboardingUiTest and RecordingFolderDetection tests.
