Feature: BeCalm full user journey catalog
  The app organizes work around people by ingesting communication sources,
  resolving participants, extracting give/take/schedule context, rebuilding the
  person graph, and rendering a local-first relationship workspace.

  Background:
    Given each scenario starts with isolated local app data
    And backend responses are controlled by fixtures or a deployed dev backend
    And the scenario owns its source files, contacts, accounts, and clock

  @happy @auth @first-run @androidTest
  Scenario: E2E-001 New user sees Korean terms and privacy first
    Given I launch BeCalm with no local session
    When the app locale is Korean
    Then the first visible screen should be the Korean terms and privacy screen
    And the continue action should be disabled until required consent is checked
    # Automation: androidTest route/locale smoke; adb screenshot smoke verifies Korean first-run copy.

  @happy @auth @first-run @androidTest
  Scenario: E2E-002 User accepts terms and reaches login
    Given I am on the terms and privacy screen
    When I check the required consent
    And I tap continue
    Then I should see the login screen
    And no source permission prompt should appear before login
    # Automation: androidTest navigation; local coverage AuthUiTest and PipaConsentUiTest.

  @happy @auth @backend
  Scenario: E2E-003 User signs in with Google and session is persisted
    Given I am on the login screen
    When I complete Google sign-in with a valid account
    Then the Supabase session should be stored
    And authenticated app bootstrap work should be scheduled once
    # Automation: device manual/adb with real Google account; unit coverage AuthViewModelSpecTest and AuthenticatedRuntimeBootstrapSpecTest.

  @error @auth @backend
  Scenario: E2E-004 Google sign-in is cancelled by the user
    Given I am on the login screen
    When I cancel Google sign-in
    Then I should remain on login
    And the app should show a recoverable Korean error or neutral idle state
    # Automation: androidTest fake auth result; local coverage AuthViewModelSpecTest.

  @error @auth @offline
  Scenario: E2E-005 Login fails while offline
    Given I am on the login screen
    When network is unavailable
    And I try to sign in
    Then I should remain on login
    And no local source sync work should be scheduled
    # Automation: MockWebServer/local fake; manual adb network toggle smoke.

  @happy @auth @routing
  Scenario: E2E-006 Returning signed-in user bypasses auth
    Given I have a valid stored session
    When I launch BeCalm
    Then I should land on the main app route or cold-sync route
    And the login screen should not flash after session resolution
    # Automation: unit SplashRouteResolverSpecTest; androidTest navigation smoke.

  @happy @auth @settings
  Scenario: E2E-007 User signs out without deleting local data
    Given I am signed in with local data
    When I sign out from Settings
    Then the session should be cleared
    And local-first raw data should remain on device
    # Automation: local integration AuthRepositoryLocalIntegrationTest and SettingsUiTest.

  @error @auth @settings @regression
  Scenario: E2E-008 User wipes local data and navigates without crashing
    Given I am signed in
    When I wipe local data and sign out
    And I navigate to Settings Sources
    Then the app should not access a user-scoped Room database before sign-in
    And I should see a signed-out or empty-safe state
    # Automation: androidTest + adb logcat; local coverage SettingsUiTest and AuthUiTest.

  @happy @onboarding @pipa @androidTest
  Scenario: E2E-009 User completes the compact onboarding setup screen
    Given I am signed in for the first time
    When I open onboarding
    And I complete required setup choices
    Then I should reach the main screen with source status visible
    # Automation: androidTest OnboardingScreenTest; local coverage OnboardingUiTest.

  @happy @onboarding @permissions @contacts
  Scenario: E2E-010 User grants contacts permission
    Given onboarding asks for contact matching permission
    When I grant contacts permission
    Then contact-based person matching should be enabled
    And source ingestion should be able to resolve contact identities
    # Automation: androidTest permission flow; unit coverage OnboardingViewModelSpecTest and PersonMatchingEngineSpecTest.

  @error @onboarding @permissions @contacts
  Scenario: E2E-011 User denies contacts permission
    Given onboarding asks for contact matching permission
    When I deny contacts permission
    Then onboarding should continue with email and phone matching only
    And People should not crash when contacts are unavailable
    # Automation: androidTest permission denial; local coverage PersonsScreenStateSourceLocalIntegrationTest.

  @happy @onboarding @permissions @notifications
  Scenario: E2E-012 User grants notification permission
    Given onboarding asks for notification permission
    When I grant notifications
    Then reminder notifications should be schedulable
    And Today should still render without waiting for alarm permission
    # Automation: androidTest permission flow; unit coverage ReminderBroadcastReceiverSpecTest.

  @error @onboarding @permissions @notifications
  Scenario: E2E-013 User denies notification permission
    Given onboarding asks for notification permission
    When I deny notifications
    Then reminders should be stored but not posted
    And the app should show a settings recovery path
    # Automation: androidTest permission denial; unit coverage VoiceUploadWorkerNotificationSpecTest.

  @happy @onboarding @sources
  Scenario: E2E-014 User skips all sources and enters the app
    Given I am signed in
    When I skip every optional source during onboarding
    Then I should still reach Today and People
    And source status should show no connected sources without errors
    # Automation: androidTest OnboardingScreenTest and SourcesListScreenTest.

  @happy @sources @gmail @backend
  Scenario: E2E-015 User connects Gmail source
    Given I am signed in
    When I complete Gmail OAuth consent
    Then Gmail should be marked connected
    And backend mail sync should be scheduled for Gmail
    # Automation: device manual OAuth; unit coverage OnboardingViewModelSpecTest and BackendMailSyncWorker tests.

  @happy @sources @outlook @backend
  Scenario: E2E-016 User connects Outlook mail source
    Given I am signed in
    When I complete Outlook mail OAuth consent
    Then Outlook mail should be marked connected
    And backend mail sync should be scheduled for Outlook mail
    # Automation: device manual OAuth; unit coverage OnboardingSourceConnectionProjectorSpecTest.

  @happy @sources @imap @naver
  Scenario: E2E-017 User connects Naver IMAP with an app password
    Given I am signed in
    When I enter valid Naver IMAP credentials
    Then Naver Mail should be marked connected
    And IMAP sync should store normalized email raw events
    # Automation: integration fixture IMAP; unit coverage SourceExtractionInputAdapterSpecTest and Imap worker tests.

  @error @sources @imap
  Scenario: E2E-018 IMAP credential validation fails
    Given I am on IMAP setup
    When I enter invalid credentials
    Then the source should remain disconnected
    And no credential should be saved as connected
    # Automation: unit OnboardingViewModelSpecTest; manual adb source setup smoke.

  @happy @sources @calendar @google
  Scenario: E2E-019 User connects Google Calendar
    Given I am signed in
    When I complete Google Calendar OAuth consent
    Then Google Calendar should be marked connected
    And calendar sync should render only yesterday-forward events
    # Automation: unit calendar worker tests; androidTest SourcesListScreenTest.

  @happy @sources @calendar @outlook
  Scenario: E2E-020 User connects Outlook Calendar
    Given I am signed in
    When I complete Outlook Calendar OAuth consent
    Then Outlook Calendar should be marked connected
    And calendar sync should follow the shared source sync route
    # Automation: unit SourceSyncPortSpecTest and Outlook calendar worker tests.

  @happy @sources @voice @permissions
  Scenario: E2E-021 User enables voice and call recording ingestion
    Given I am signed in
    When I grant media or recording folder access
    Then supported voice sources should be enabled
    And runtime sync should enqueue the source workers once
    # Automation: adb MediaStore smoke; unit WorkSchedulerImplSpecTest and RuntimeSyncSourceResolverSpecTest.

  @happy @sources @settings
  Scenario: E2E-022 Connected source state is identical in onboarding and Settings
    Given I connect or skip sources during onboarding
    When I open Settings Sources
    Then each source should show the same connection state
    And reconnect or disconnect actions should match source type
    # Automation: androidTest SourcesListScreenTest; local SourcesLocalIntegrationTest.

  @happy @sources @disconnect
  Scenario: E2E-023 User disconnects a source from Settings
    Given Gmail is connected
    When I disconnect Gmail from Settings
    Then its source status should become disconnected
    And future foreground catch-up should exclude Gmail
    # Automation: unit SourceDetailViewModelSpecTest and RuntimeSyncSourceResolverSpecTest.

  @happy @sync @cold-sync
  Scenario: E2E-024 Initial cold sync starts after onboarding
    Given I finish onboarding with at least one source enabled
    When I enter the app for the first time
    Then cold sync should start stage 1
    And progress should be visible without blocking navigation forever
    # Automation: unit ColdSyncViewModelSpecTest; androidTest TodayTimelineScreenTest.

  @happy @sync @cold-sync
  Scenario: E2E-025 Cold sync can be skipped after delay
    Given cold sync is still running
    When the skip delay elapses
    And I tap skip for now
    Then I should reach the main app
    And deferred sync should continue in background
    # Automation: unit ColdSyncViewModelSpecTest and ColdSyncLifecycleCoordinatorSpecTest.

  @error @sync @backend
  Scenario: E2E-026 Backend sync times out during raw upload
    Given raw ingestion events are pending upload
    When the backend upload request times out
    Then the worker should retry with backoff
    And the source status should show a recoverable sync error
    # Automation: unit UploadWorkerSpecTest and UploadBackoffSpecTest; adb logcat smoke.

  @happy @sync @foreground
  Scenario: E2E-027 Foreground refresh coalesces multiple source refreshes
    Given multiple sources are enabled
    When I pull to refresh repeatedly
    Then work should be enqueued using unique source keys
    And duplicate refresh work should be coalesced
    # Automation: unit WorkSchedulerImplSpecTest and UniqueWorkKeysSpecTest.

  @happy @sync @batch
  Scenario: E2E-028 Batch upload is used when multiple raw events are pending
    Given more than one raw ingestion event is pending
    When upload work runs
    Then the batch endpoint should be used
    And successful rows should be marked synced
    # Automation: local UploadWorkerLocalIntegrationTest and RawIngestionRepositoryLocalIntegrationTest.

  @happy @sync @single
  Scenario: E2E-029 Single extraction runs through the same normalized pipeline
    Given one source event is pending structured extraction
    When its source upload worker runs
    Then it should build a SourceExtractionRequest from NormalizedSourceEvent
    And StructuredExtractionPersister should save commitments and participants
    # Automation: unit SourceExtractionInputAdapterSpecTest and source upload worker specs.

  @happy @import @meeting @audio
  Scenario: E2E-030 User imports meeting audio from the global evidence sheet
    Given I am signed in
    When I tap the global plus button
    And I choose meeting audio
    And I select a supported audio file
    Then the file should be stored locally as a meeting artifact
    And meeting upload work should be enqueued
    # Automation: androidTest evidence sheet; unit MeetingImportFilePolicySpecTest and upload worker specs.

  @error @import @meeting @transcript
  Scenario: E2E-031 Meeting transcript import is not exposed
    Given I am signed in
    When I open evidence import
    Then meeting transcript should not be an available option
    And no text extraction work should be enqueued
    # Automation: androidTest evidence sheet; backend extraction boundary tests.

  @error @import @meeting
  Scenario: E2E-032 User selects unsupported meeting file type
    Given I am signed in
    When I select an unsupported meeting file
    Then the file should be rejected before raw event creation
    And no upload work should be enqueued
    # Automation: unit MeetingImportFilePolicySpecTest; androidTest file picker smoke pending.

  @happy @import @message-screenshot
  Scenario: E2E-033 User imports messenger screenshot
    Given I am signed in
    When I choose messenger screenshot from evidence import
    And I select a supported image
    Then the image should be resized to OCR-safe JPEG
    And message screenshot upload work should be enqueued
    # Automation: unit SourceImportRepositorySpecTest and MessageScreenshotUploadWorkerSpecTest; androidTest evidence sheet.

  @error @import @message-screenshot
  Scenario: E2E-034 Oversized screenshot is rejected
    Given I am signed in
    When I select an oversized screenshot
    Then no raw event should be created
    And the user should see a recoverable import error
    # Automation: unit SourceImportRepositorySpecTest.

  @error @import @manual-text
  Scenario: E2E-035 Manual text evidence is not exposed
    Given I am signed in
    When I open evidence import
    Then direct text should not be an available option
    And no manual_text raw event should be created
    # Automation: androidTest evidence sheet; unit SourceTypeContractTest.

  @error @import @manual-text
  Scenario: E2E-036 Text extraction bypass is rejected at the backend boundary
    Given an authenticated client
    When it posts body_text to the extraction endpoint
    Then the request should fail with 422
    And no raw event should be created
    # Automation: androidTest evidence dialog; unit SourceImportRepositorySpecTest.

  @happy @import @voice
  Scenario: E2E-037 Supported call recording file is detected automatically
    Given voice or call recording ingestion is enabled
    When a supported call recording appears in MediaStore
    Then one raw event should be created
    And upload/extraction work should be enqueued
    # Automation: adb MediaStore fixture; unit MediaStoreWorkerSpecTest.

  @error @import @voice
  Scenario: E2E-038 Unsupported recording file is ignored or quarantined
    Given voice ingestion is enabled
    When an unsupported or corrupt audio file appears
    Then it should not create a trackable commitment
    And the user-visible source state should remain recoverable
    # Automation: unit MediaStoreWorkerSpecTest and VoiceFailureNotifierSpecTest.

  @happy @pipeline @email @gmail
  Scenario: E2E-039 Gmail message becomes normalized extraction input
    Given Gmail sync returns a person-to-person business message
    When backend mail sync stores the raw email
    Then source participants should include sender and receiver roles
    And extraction should produce give/take only for next actions
    # Automation: local BackendMailSourceEventParticipantPipelineLocalIntegrationTest and SourceExtractionInputAdapterSpecTest.

  @happy @pipeline @email @imap
  Scenario: E2E-040 Naver IMAP message follows the same normalized extraction input
    Given Naver IMAP sync returns a business message
    When the worker stores the email body and metadata
    Then the normalized request shape should match Gmail except source type
    And person identity resolution should use email and contact hints
    # Automation: unit SourceExtractionInputAdapterSpecTest and EmailPersonRefSpecTest.

  @error @pipeline @email @filtering
  Scenario: E2E-041 Bot notification email is filtered out of rendering
    Given email sync receives a bot notification or marketing message
    When classification and extraction run
    Then raw storage may remain local-first
    But no user-facing commitment card should be rendered
    # Automation: backend fixture pytest; local worker fixture pending.

  @happy @pipeline @calendar
  Scenario: E2E-042 Calendar event creates schedule context
    Given calendar sync receives a future or yesterday event
    When it is normalized into source participants
    Then participants should be available for person timeline
    And duplicate source cards should prefer the original source event
    # Automation: unit calendar worker tests and PersonDetailViewModelSpecTest.

  @error @pipeline @calendar
  Scenario: E2E-043 Old calendar history before yesterday is hidden
    Given calendar sync contains events older than yesterday
    When person detail projection is built
    Then those old calendar events should not render
    And current/future events should still render
    # Automation: unit PersonDetailViewModelSpecTest.

  @happy @pipeline @extraction
  Scenario: E2E-044 Structured extraction persists commitments and participants atomically
    Given a source extraction response contains participants and commitments
    When StructuredExtractionPersister saves the response
    Then commitments, source participants, commitment participants, and dirty rows should be persisted
    And relation refresh should be notified once
    # Automation: local AiPersonPipelineLocalIntegrationTest and SourceRelationRefreshCoordinatorSpecTest.

  @error @pipeline @extraction
  Scenario: E2E-045 Extraction response has no actionable items
    Given a source event has participants but no give/take/schedule items
    When extraction completes
    Then source participants should remain available for relationship context
    But no commitment feed card should be created
    # Automation: local pipeline fixture pending; unit CommitmentManagementProjector tests.

  @happy @people @matching
  Scenario: E2E-046 Contact identity auto-merges email and phone for the same person
    Given contact data links a phone number and email address
    When source events reference either identity
    Then both identities should resolve to one person
    And People should show one contact row
    # Automation: unit PersonIdentityResolverSpecTest and PersonMatchingEngineSpecTest.

  @happy @people @matching
  Scenario: E2E-047 User confirms an unresolved participant as an existing person
    Given a source event has an unresolved participant
    When I open person matching review
    And I confirm the suggested person
    Then the participant should become resolved
    And person interactions should rebuild for that person
    # Automation: local PersonManualMatchPipelineLocalIntegrationTest; adb review route smoke pending.

  @error @people @matching
  Scenario: E2E-048 User rejects an incorrect person match suggestion
    Given a source event has an unresolved participant
    When I reject the suggested person
    Then the participant should remain unresolved or become a new person candidate
    And no existing person timeline should be polluted
    # Automation: local person matching integration pending.

  @happy @people @list
  Scenario: E2E-049 People tab renders contact rows only
    Given resolved people have work interactions
    When I open People
    Then the list should show contact-like identity rows
    And work snippets should not appear until person detail
    # Automation: androidTest PersonsScreenTest and local PersonsUiTest.

  @happy @people @detail
  Scenario: E2E-050 Person detail renders source timeline and extracted work context
    Given a person has email, call, meeting, screenshot, and manual text events
    When I open that person detail
    Then timeline cards should be grouped by source event
    And each card should show give, take, and schedule summaries
    # Automation: androidTest PersonDetailScreenTest and unit PersonDetailViewModelSpecTest.

  @happy @people @detail @decision
  Scenario: E2E-051 Decision items enrich memory but do not clutter timeline cards
    Given extraction returns a decision item for a person
    When person detail and commitment feed are projected
    Then decision should not render as a primary action card
    And memory input should include the decision as relationship context
    # Automation: unit PersonDetailViewModelSpecTest and PersonMemoryInputCollectorLocalIntegrationTest.

  @happy @people @raw
  Scenario: E2E-052 User opens original source from a person timeline card
    Given a person detail card has a raw event id
    When I tap the source card
    Then raw event detail should show source badge, title, snippet, and local original availability
    # Automation: androidTest PersonDetailScreenTest; unit RawEventDetailViewModelSpecTest.

  @happy @commitments @feed
  Scenario: E2E-053 Commitments tab shows latest person interaction feed
    Given commitments exist for multiple people
    When I open Commitments
    Then cards should be grouped or labeled by person
    And sorted by due recency and source occurrence policy
    # Automation: androidTest CommitmentManagementScreenTest and unit CommitmentManagementViewModelSpecTest.

  @happy @commitments @filter
  Scenario: E2E-054 User filters commitments by give, take, schedule, and closed
    Given the commitment feed has multiple item types
    When I tap each filter
    Then only the corresponding primary feed items should render
    And decision context should remain hidden from primary filters
    # Automation: unit CommitmentManagementViewModelSpecTest and androidTest CommitmentManagementScreenTest.

  @happy @commitments @edit @crud
  Scenario: E2E-055 User edits a commitment card
    Given a commitment is visible in detail
    When I open edit
    And I save a changed title, due time, or counterparty
    Then local Room should be the source of truth
    And the edit should be mirrored through REST CRUD
    # Automation: androidTest CommitmentSheetsTest; unit CommitmentEditViewModelSpecTest and RestCrudContractSpecTest.

  @happy @commitments @undo
  Scenario: E2E-056 User completes a commitment and undoes it
    Given an open commitment is visible
    When I mark it completed
    And I tap undo within the snackbar window
    Then the commitment should return to its prior state
    And reminder scheduling should be restored if needed
    # Automation: unit CommitmentManagementViewModelSpecTest and OverdueSweepWorkerSpecTest.

  @happy @commitments @delete
  Scenario: E2E-057 User deletes or cancels a commitment
    Given an open commitment is visible
    When I cancel or delete it from detail
    Then it should leave active Today and Commitment lists
    And closed history should remain recoverable according to policy
    # Automation: unit CommitmentDetailViewModelSpecTest and CommitmentManagementViewModelSpecTest.

  @happy @today @timeline
  Scenario: E2E-058 Today shows only work due today and current schedule
    Given I have due actions and schedule items for today
    When I open Today
    Then timed items should be in timeline order
    And untimed items should be in the needs-time section
    # Automation: androidTest TodayTimelineScreenTest and unit TodayViewModelSpecTest.

  @happy @today @edit
  Scenario: E2E-059 User adds missing due time from Today
    Given Today shows an action with no exact due time
    When I tap add time
    Then commitment edit should open
    And saving the exact due time should update Today
    # Automation: androidTest TodayTimelineScreenTest and CommitmentSheetsTest.

  @error @today @empty
  Scenario: E2E-060 Today is empty
    Given no actionable give, take, or schedule item exists for today
    When I open Today
    Then I should see a calm empty state
    And source status should still be available
    # Automation: androidTest TodayTimelineScreenTest.

  @happy @notifications @reminder
  Scenario: E2E-061 Due reminder notification fires at the configured lead time
    Given notifications are granted
    And an exact due commitment is scheduled
    When the reminder time arrives
    Then Android should post a reminder notification
    And tapping it should open the commitment or Today context
    # Automation: unit ReminderBroadcastReceiverSpecTest; adb alarm/notification smoke pending.

  @error @notifications @reminder
  Scenario: E2E-062 No-time schedule does not fire a due reminder
    Given a schedule or commitment has no exact time
    When reminder scheduling runs
    Then no due reminder alarm should be scheduled
    And the item should remain visible only in relevant UI sections
    # Automation: unit OverdueSweepSchedulingSpecTest and ReminderBroadcastReceiverSpecTest.

  @happy @memory @person
  Scenario: E2E-063 Person memory is generated after graph changes
    Given a resolved person has new source interactions
    When PersonInteractionIndexWorker rebuilds the projection
    Then ProfileMemoryWorker should be enqueued for that person
    And memory.md should be written locally from structured evidence
    # Automation: local PersonInteractionIndexWorkerLocalIntegrationTest and ProfileMemoryWorkerLocalIntegrationTest.

  @error @memory @offline
  Scenario: E2E-064 Person memory upload fails offline but local memory remains
    Given person memory generation succeeds locally
    When Railway upload fails
    Then memory.md should remain available locally
    And upload should remain retryable without blocking UI
    # Automation: local ProfileMemoryWorkerLocalIntegrationTest and PersonMemoryRemoteRepositoryLocalIntegrationTest.

  @happy @privacy @retention
  Scenario: E2E-065 User deletes raw originals before a selected date
    Given local raw originals exist across multiple dates
    When I delete originals before a selected date
    Then matching files should be removed from local storage
    And structured relationship projections should remain safe to render
    # Automation: local RetentionSweepWorkerLocalIntegrationTest and PrivacyManagementViewModelSpecTest.

  @happy @privacy @export
  Scenario: E2E-066 User exports local privacy data
    Given local activity and privacy data exists
    When I export from privacy settings
    Then the app should create a user-readable export file
    And no unsupported source secret should be leaked
    # Automation: local PrivacyDataExporterLocalIntegrationTest and androidTest PrivacySubscreensTest.

  @happy @privacy @pipa
  Scenario: E2E-067 User reviews PIPA activity log
    Given privacy-affecting actions have occurred
    When I open the activity log
    Then each entry should show a Korean user-readable explanation
    And the log should be local-only
    # Automation: androidTest PrivacyManagementScreenTest and PrivacySubscreensTest.

  @error @privacy @raw
  Scenario: E2E-068 Original file is missing when user opens raw detail
    Given a raw event exists but its original file was deleted
    When I open raw event detail
    Then the UI should show a deleted-original notice
    And no crash should occur
    # Automation: unit RawEventDetailViewModelSpecTest and androidTest source viewer pending.

  @happy @settings @source-management
  Scenario: E2E-069 User views source detail and recent events
    Given a source has recent raw events and status
    When I open the source detail screen
    Then status, actions, and recent events should render
    And source-specific reconnect options should be correct
    # Automation: androidTest SourceDetailScreenTest and unit SourceDetailViewModelSpecTest.

  @error @settings @source-management
  Scenario: E2E-070 Source sync error is visible but does not block other sources
    Given one source has sync error
    And another source is connected
    When I open Sources or Today
    Then the failed source should be visibly recoverable
    And connected sources should still render normally
    # Automation: androidTest SourcesListScreenTest and TodayTimelineScreenTest.

  @happy @navigation @deep-link
  Scenario: E2E-071 Notification or deep link opens a commitment detail
    Given a valid commitment deep link exists
    When I open it from Android
    Then the app should route to commitment detail after auth gating
    And invalid ids should show a recoverable empty/error state
    # Automation: unit AppDeepLinksSpecTest; androidTest navigation pending.

  @error @resilience @process-death
  Scenario: E2E-072 App process dies during background work
    Given upload or extraction work is running
    When the app process is killed
    Then WorkManager should preserve retryable work
    And launching the app should not duplicate source events
    # Automation: adb process-kill smoke pending; unit WorkSchedulerImplSpecTest and UploadWorkerSpecTest.

  @happy @meeting @person-matching @memory
  Scenario: E2E-073 Meeting audio speaker review matches an existing person
    Given a meeting audio upload has a Clova speaker preview
    And an unresolved speaker has a name, company, and work context similar to an existing person
    When I choose my speaker and confirm the recommended existing person
    Then the extraction should use the reviewed speaker mapping
    And the person detail timeline should show the meeting interaction with local memory evidence
    # Automation: unit PersonsScreenStateSourceLocalIntegrationTest, Checkpoint4ExtractionPersistenceSpecTest, PersonMemoryMarkdownSpecTest; androidTest meeting speaker review pending.
