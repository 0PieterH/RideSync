import com.nolimitscoaster.*;
import nlvm.math3d.*;

public class TimelineAudio extends Script implements TrackTriggerListener {

    // =========================================================================
    // TRAIN SELECTION
    // =========================================================================
    // TRAIN_INDEX names the config SCO for this script instance. The actual
    // train this timeline targets is read from the timeline data ("trainIndex",
    // Settings -> Project -> Train index), so it can be changed in the editor
    // without recompiling the script.
    private static final int TRAIN_INDEX = 0;
    private int trainIndex = 0;

    // =========================================================================
    // TIMELINE FILES (relative to classpath / NL2 package)
    // =========================================================================
    private static final String TIMELINE_FILE_BIN = "config/timeline.bin";

    // Binary header: "RNRC" (4) + version (1) + trackCount (1) + clipCount (2)
    //                + regionCount (2) + triggerCount (2) + reserved (2) + duration (4) = 18 bytes
    private static final int HEADER_SIZE = 18;

    private int fileVersion;

    // =========================================================================
    // AUDIO SETTINGS (parsed from the timeline extinction footer; the editor's
    // Settings -> Audio Distance section writes them. Defaults mirror the
    // original OnboardAudio constants.)
    // =========================================================================
    private float audioRefDist = 3.0f;
    private float audioRolloff  = 5.0f;

    // Clips are a DAW-style editing layer only. In-game playback is driven
    // exclusively by REGIONS (track triggers / block states / time). The
    // timeline clock advances for time-launched regions; clips are never
    // started by the script.

    // =========================================================================
    // CLIP / TRIGGER / REGION MAX CAPS
    // =========================================================================
    private static final int MAX_CLIPS       = 200;
    private static final int MAX_TRIGGERS    = 50;
    private static final int MAX_REGIONS     = 50;

    // Sentinel filename that marks a region as a timeline-clip CONTAINER: on
    // launch it plays every clip inside its [Start, End) range at their
    // timeline offsets (layered or sequential) instead of one audio file.
    private static final String CONT_CLIP_MARKER = "__CLIPS__";

    // Audio folder from the timeline (binary v4 / text "# AudioDir:").
    // May be a subfolder relative to the script classpath (e.g. "Audio");
    // absolute paths do not resolve in NL2 and are ignored at load time.
    private String audioDir = "";

    // =========================================================================
    // DATA STRUCTURES
    // =========================================================================

    // Timeline clips
    private int     clipCount;
    private int[]   clipTrackIndex;
    private float[] clipStartTime;
    private float[] clipDuration;      // editor-known length (v3+); 120 fallback
    private int[]   clipPlayMode;     // 0 = once, 1 = loop
    private float[] clipVolume;
    private String[] clipFilename;

    // Regions (time bars + launchable audio via block state / track trigger)
    private int     regionCount;
    private String[] regionName;
    private float[] regionStart;
    private float[] regionEnd;
    private int[]   regionLaunchBy;     // 0 = time, 1 = block, 2 = trigger
    private String[] regionBlockName;
    private int[]   regionStateValue;
    private String[] regionTrackTrigger;
    private String[] regionFilename;
    private int[]   regionPlayMode;
    private int[]   regionRole;        // 0 = normal, 1 = music (ducked), 2 = voice (ducks music)
    private float[] regionVolume;
    private float[] regionFadeTime;

    // Runtime region audio: [regionIndex][carIndex]
    private StaticSound[][] regionSounds;

    // Region launch state
    private bool[] regionPlaying;
    private bool[] regionFiredTime;     // time-based regions already fired
    private float[] regionGain;
    private float[] regionTargetGain;
    private float[] regionFadeRate;
    private bool[] regionFadeOut;       // region is fading down due to a stop command
    // Dedicated stop-command fade engine (time-based, independent of the ramp).
    private bool[]  regionStopFade;
    private float[] regionStopFadeFrom;
    private float[] regionStopFadeElapsed;
    private float[] regionStopFadeDur;
    // Ducking is a separate multiplier on top of the fade gain, so a voice
    // starting/ending while a region fades in/out never clobbers the fade.
    private float[] regionDuck;         // current duck multiplier (1 = full)
    private float[] regionDuckTarget;   // duckDepth while a voice plays, else 1
    private float[] regionDuckRate;

    // Container regions (regionFilename == CONT_CLIP_MARKER): play the clips
    // that lie inside the region's [Start, End) range at their offsets.
    private bool[]   regionIsContainer;    // marker region -> plays contained clips
    private int[][]  regionContainedClips; // clip indices per container region
    private bool[][] regionContainedFired; // per-clip fire state for the current pass
    private float[]  regionElapsed;        // seconds since the container launched

    // Region "follow action": the name of another region to start automatically
    // when THIS region finishes playing (once mode) or its container sequence
    // completes. "" = no follow. Resolved to an index after parsing.
    private String[] regionFollowName;
    private int[]    regionFollow;         // resolved target region index (-1 none)

    // Crossfade looping: a loop region with a crossfade time > 0 alternates two
    // handles of the same file (A/B) and gain-blends them across the loop seam,
    // so each repetition overlaps the next instead of restarting abruptly.
    private StaticSound[][] regionSoundsB;   // second handle, [region][car]
    private float[] regionLoopCrossfade;     // crossfade seconds (0 = off)
    private float[] regionLoopPos;           // elapsed within the active handle
    private float[] regionLoopXF;            // crossfade progress (seconds; 0 = none)
    private bool[]  regionLoopActiveB;       // true when handle B is the active one

    // Trigger definitions (name -> filename mapping)
    private int     triggerDefCount;
    private String[] triggerDefName;
    private String[] triggerDefFilename;
    private int[]   triggerDefPlayMode;
    private float[] triggerDefVolume;

    // Block references for region block-launch polling
    private Block[] regionBlocks;
    private int[]   previousRegionBlockState;

    // Track triggers for region trigger-launch
    private TrackTrigger[] regionTriggers;

    // Runtime clip audio: [clipIndex][carIndex]
    private StaticSound[][] clipSounds;

    // Runtime trigger audio: [triggerIndex][carIndex]
    private StaticSound[][] triggerSounds;

    // Active playback state per clip
    private bool[] clipPlaying;
    private bool[] clipLooping;
    private float[] clipElapsed;      // seconds a clip has been playing (for trim)

    // Active playback state per trigger
    private bool[] triggerPlaying;

    // Timeline state
    private float timelineTime;
    private float timelineDuration;
    private bool timelineRunning;

    // Track triggers on the coaster
    private TrackTrigger[] trackTriggers;
    private int triggerCount;

    // Coaster / Train
    private Coaster coaster;
    private Train train;
    private int carCount;

    // =========================================================================
    // EXTINCTION (stop-triggered fade-out) SETTINGS
    // Stored in the timeline record (optional footer). Mirrors OnboardAudio1's
    // section 7: when the train has been stopped for `stoppedSeconds`, fade out
    // all looping regions, then (optionally) lock a complete mute until the
    // player restores audio by pressing the Action key in the station block.
    // =========================================================================
    private bool   extEnabled;
    private String extArmBlock;        // block that arms the extinction timer
    private int    extArmState;        // desired block state to arm
    private float  extStoppedSeconds;  // stopped duration before fading loops
    private float  extFadeSeconds;     // fade duration of looping regions
    private bool   extMuteLock;        // stay muted until manual restore
    private String extRestoreBlock;    // block where Action key restores
    private int    extRestoreState;    // block state meaning "in station"
    private String extRestoreTrigger;  // watch-end trigger (marks end of extinction section)
    private String extRestoreRegion;   // region started when audio is restored ("" = none)
    private int    extRestoreRegionRef;// resolved region index (-1 none)
    // Block-state mode: true = scripted operation mode (custom states via
    // getState); false = unscripted/normal mode (built-in states via
    // getNormalModeState(PROTOCOL_V1)). Applies to block regions and extinction.
    private bool   extScriptedBlocks;

    private Block       extArmBlockRef;
    private Block       extRestoreBlockRef;
    private TrackTrigger extRestoreTriggerRef;
    private int         prevExtArmState;
    private bool        extRideActive;
    private bool        extCompletelyMuted;
    private bool        extFadeDisabled;
    private float       extRideActiveTime;
    private bool        wasActionPressed;
    private bool        promptShown;

    // =========================================================================
    // DUCKING (wildlines duck the music) - mirrors OnboardAudio1
    // =========================================================================
    private float  duckDepth;
    private float  duckAttack;
    private float  duckRelease;
    private bool   duckingActive;
    private float  duckTimer;

    // =========================================================================
    // STATION WAIT MUSIC (per block region, zone-neutral) - replaces the old
    // global "Zone 5" idle music. Any block-launched region with a waitFile
    // becomes a wait zone: stopped there for its waitStop seconds -> fade ride
    // music out and fade the wait channel in; on departure, fade it back out.
    // =========================================================================
    private String[] regionWaitFile;          // "" = no wait music for this region
    private float[]  regionWaitStop;          // seconds stopped before wait track
    private float[]  regionWaitFadeIn;        // wait track fade-in
    private float[]  regionWaitFadeOut;       // wait track fade-out on departure
    private float[]  regionWaitRideFadeOut;   // ride music fade-out when wait starts
    private int[]    regionWaitState;         // block state meaning stopped/waiting
    private bool[]   regionWaitOnce;          // true = hold music plays once (not looped)
    private bool[]   regionHoldZone;          // region is a hold-music zone (its clips are the hold music)
    private bool[]   regionRestoreRide;       // fade ride music back in on departure
    private StaticSound[][] regionWaitSounds; // [region][car], loaded on demand

    private int    armedWait;                 // currently active wait region, -1 none
    private bool[] waitPrevIn;                // train was in region block last frame
    private bool[] waitPlayed;                // wait track already triggered
    private float[] waitStopTime;             // accumulated stopped time
    private bool     waitFading;
    private float    waitGain;
    private float    waitTarget;
    private float    waitRate;
    private int      waitFadeRegion;          // region whose sounds the fade controls

    // =========================================================================
    // CONFIRM BEEP ON ACTION RESTORE + DISABLE-FADE TRIGGER
    // =========================================================================
    private String extConfirmFile;
    private String extDisableFadeTrigger;
    private StaticSound[] confirmSounds;
    private TrackTrigger disableFadeTriggerRef;

    // =========================================================================
    // PER-TRAIN STATE (all-trains mode)
    // With "All trains" on, one script instance runs the whole timeline for
    // every train independently. The working fields above always point at the
    // currently active train's arrays; setActiveTrain() swaps them in.
    // =========================================================================
    private int  trainCount;
    private bool allTrains;
    private int  activeTrain;
    private bool[][] pt_regionPlaying;
    private bool[][] pt_regionFiredTime;
    private float[][] pt_regionGain;
    private float[][] pt_regionTargetGain;
    private float[][] pt_regionFadeRate;
    private bool[][] pt_regionFadeOut;
    private bool[][] pt_regionStopFade;
    private float[][] pt_regionStopFadeFrom;
    private float[][] pt_regionStopFadeElapsed;
    private float[][] pt_regionStopFadeDur;
    private float[][] pt_regionDuck;
    private float[][] pt_regionDuckTarget;
    private float[][] pt_regionDuckRate;
    private bool[][][] pt_regionContainedFired;
    private float[][] pt_regionElapsed;
    private float[][] pt_regionLoopPos;
    private float[][] pt_regionLoopXF;
    private bool[][] pt_regionLoopActiveB;
    private bool[][] pt_clipPlaying;
    private bool[][] pt_clipLooping;
    private float[][] pt_clipElapsed;
    private bool[][] pt_triggerPlaying;
    private float[] pt_timelineTime;
    private int[][] pt_previousRegionBlockState;
    private bool[] pt_extRideActive;
    private bool[] pt_extCompletelyMuted;
    private bool[] pt_extFadeDisabled;
    private float[] pt_extRideActiveTime;
    private bool[] pt_wasActionPressed;
    private bool[] pt_promptShown;
    private int[] pt_prevExtArmState;
    private bool[] pt_duckingActive;
    private float[] pt_duckTimer;
    private int[] pt_armedWait;
    private bool[][] pt_waitPrevIn;
    private bool[][] pt_waitPlayed;
    private float[][] pt_waitStopTime;
    private bool[] pt_waitFading;
    private float[] pt_waitGain;
    private float[] pt_waitTarget;
    private float[] pt_waitRate;
    private int[] pt_waitFadeRegion;
    private StaticSound[][][] pt_regionSounds;
    private StaticSound[][][] pt_regionSoundsB;
    private StaticSound[][][] pt_clipSounds;
    private StaticSound[][][] pt_triggerSounds;
    private StaticSound[][] pt_confirmSounds;
    private StaticSound[][][] pt_regionWaitSounds;

    // Save the scalar (by-value) working fields back to a train's storage.
    private void saveTrainScalars(int t) {
        pt_timelineTime[t] = timelineTime;
        pt_prevExtArmState[t] = prevExtArmState;
        pt_extRideActive[t] = extRideActive;
        pt_extCompletelyMuted[t] = extCompletelyMuted;
        pt_extFadeDisabled[t] = extFadeDisabled;
        pt_extRideActiveTime[t] = extRideActiveTime;
        pt_wasActionPressed[t] = wasActionPressed;
        pt_promptShown[t] = promptShown;
        pt_duckingActive[t] = duckingActive;
        pt_duckTimer[t] = duckTimer;
        pt_armedWait[t] = armedWait;
        pt_waitFading[t] = waitFading;
        pt_waitGain[t] = waitGain;
        pt_waitTarget[t] = waitTarget;
        pt_waitRate[t] = waitRate;
        pt_waitFadeRegion[t] = waitFadeRegion;
    }

    // Make train t the active one: the working fields then point at its state.
    private void setActiveTrain(int t) {
        if (t < 0 || t >= trainCount) return;
        if (activeTrain >= 0) saveTrainScalars(activeTrain);
        activeTrain = t;
        train = coaster.getTrainAt(t);
        carCount = (train != null) ? train.getCarCount() : 0;
        regionPlaying = pt_regionPlaying[t];
        regionFiredTime = pt_regionFiredTime[t];
        regionGain = pt_regionGain[t];
        regionTargetGain = pt_regionTargetGain[t];
        regionFadeRate = pt_regionFadeRate[t];
        regionFadeOut = pt_regionFadeOut[t];
        regionStopFade = pt_regionStopFade[t];
        regionStopFadeFrom = pt_regionStopFadeFrom[t];
        regionStopFadeElapsed = pt_regionStopFadeElapsed[t];
        regionStopFadeDur = pt_regionStopFadeDur[t];
        regionDuck = pt_regionDuck[t];
        regionDuckTarget = pt_regionDuckTarget[t];
        regionDuckRate = pt_regionDuckRate[t];
        regionContainedFired = pt_regionContainedFired[t];
        regionElapsed = pt_regionElapsed[t];
        regionLoopPos = pt_regionLoopPos[t];
        regionLoopXF = pt_regionLoopXF[t];
        regionLoopActiveB = pt_regionLoopActiveB[t];
        clipPlaying = pt_clipPlaying[t];
        clipLooping = pt_clipLooping[t];
        clipElapsed = pt_clipElapsed[t];
        triggerPlaying = pt_triggerPlaying[t];
        timelineTime = pt_timelineTime[t];
        previousRegionBlockState = pt_previousRegionBlockState[t];
        extRideActive = pt_extRideActive[t];
        extCompletelyMuted = pt_extCompletelyMuted[t];
        extFadeDisabled = pt_extFadeDisabled[t];
        extRideActiveTime = pt_extRideActiveTime[t];
        wasActionPressed = pt_wasActionPressed[t];
        promptShown = pt_promptShown[t];
        prevExtArmState = pt_prevExtArmState[t];
        duckingActive = pt_duckingActive[t];
        duckTimer = pt_duckTimer[t];
        armedWait = pt_armedWait[t];
        waitPrevIn = pt_waitPrevIn[t];
        waitPlayed = pt_waitPlayed[t];
        waitStopTime = pt_waitStopTime[t];
        waitFading = pt_waitFading[t];
        waitGain = pt_waitGain[t];
        waitTarget = pt_waitTarget[t];
        waitRate = pt_waitRate[t];
        waitFadeRegion = pt_waitFadeRegion[t];
        regionSounds = pt_regionSounds[t];
        regionSoundsB = pt_regionSoundsB[t];
        clipSounds = pt_clipSounds[t];
        triggerSounds = pt_triggerSounds[t];
        confirmSounds = pt_confirmSounds[t];
        regionWaitSounds = pt_regionWaitSounds[t];
    }

    // Index of a Train object on the coaster (-1 if not found).
    private int trainIndexOf(Train t) {
        if (t == null) return -1;
        int idx = t.getTrainIndex();
        if (idx < 0 || idx >= trainCount) return -1;
        return idx;
    }

    // Allocate per-train storage. Train 0 reuses the arrays already allocated by
    // the parser; the other trains get fresh copies.
    private void allocPerTrainState() {
        int n = trainCount;
        pt_regionPlaying = new bool[n][];       pt_regionPlaying[0] = regionPlaying;       for (int t = 1; t < n; t++) pt_regionPlaying[t] = new bool[regionCount];
        pt_regionFiredTime = new bool[n][];     pt_regionFiredTime[0] = regionFiredTime;   for (int t = 1; t < n; t++) pt_regionFiredTime[t] = new bool[regionCount];
        pt_regionGain = new float[n][];         pt_regionGain[0] = regionGain;             for (int t = 1; t < n; t++) pt_regionGain[t] = new float[regionCount];
        pt_regionTargetGain = new float[n][];   pt_regionTargetGain[0] = regionTargetGain; for (int t = 1; t < n; t++) pt_regionTargetGain[t] = new float[regionCount];
        pt_regionFadeRate = new float[n][];     pt_regionFadeRate[0] = regionFadeRate;     for (int t = 1; t < n; t++) pt_regionFadeRate[t] = new float[regionCount];
        pt_regionFadeOut = new bool[n][];       pt_regionFadeOut[0] = regionFadeOut;       for (int t = 1; t < n; t++) pt_regionFadeOut[t] = new bool[regionCount];
        pt_regionStopFade = new bool[n][];      pt_regionStopFade[0] = regionStopFade;     for (int t = 1; t < n; t++) pt_regionStopFade[t] = new bool[regionCount];
        pt_regionStopFadeFrom = new float[n][]; pt_regionStopFadeFrom[0] = regionStopFadeFrom; for (int t = 1; t < n; t++) pt_regionStopFadeFrom[t] = new float[regionCount];
        pt_regionStopFadeElapsed = new float[n][]; pt_regionStopFadeElapsed[0] = regionStopFadeElapsed; for (int t = 1; t < n; t++) pt_regionStopFadeElapsed[t] = new float[regionCount];
        pt_regionStopFadeDur = new float[n][]; pt_regionStopFadeDur[0] = regionStopFadeDur; for (int t = 1; t < n; t++) pt_regionStopFadeDur[t] = new float[regionCount];
        pt_regionDuck = new float[n][];         pt_regionDuck[0] = regionDuck;             for (int t = 1; t < n; t++) { pt_regionDuck[t] = new float[regionCount]; for (int i = 0; i < regionCount; i++) pt_regionDuck[t][i] = 1.0f; }
        pt_regionDuckTarget = new float[n][];   pt_regionDuckTarget[0] = regionDuckTarget; for (int t = 1; t < n; t++) { pt_regionDuckTarget[t] = new float[regionCount]; for (int i = 0; i < regionCount; i++) pt_regionDuckTarget[t][i] = 1.0f; }
        pt_regionDuckRate = new float[n][];     pt_regionDuckRate[0] = regionDuckRate;     for (int t = 1; t < n; t++) pt_regionDuckRate[t] = new float[regionCount];
        pt_regionContainedFired = new bool[n][][]; pt_regionContainedFired[0] = regionContainedFired; for (int t = 1; t < n; t++) pt_regionContainedFired[t] = new bool[regionCount][];
        pt_regionElapsed = new float[n][];      pt_regionElapsed[0] = regionElapsed;       for (int t = 1; t < n; t++) pt_regionElapsed[t] = new float[regionCount];
        pt_regionLoopPos = new float[n][];      pt_regionLoopPos[0] = regionLoopPos;       for (int t = 1; t < n; t++) pt_regionLoopPos[t] = new float[regionCount];
        pt_regionLoopXF = new float[n][];       pt_regionLoopXF[0] = regionLoopXF;         for (int t = 1; t < n; t++) pt_regionLoopXF[t] = new float[regionCount];
        pt_regionLoopActiveB = new bool[n][];   pt_regionLoopActiveB[0] = regionLoopActiveB; for (int t = 1; t < n; t++) pt_regionLoopActiveB[t] = new bool[regionCount];
        pt_clipPlaying = new bool[n][];         pt_clipPlaying[0] = clipPlaying;           for (int t = 1; t < n; t++) pt_clipPlaying[t] = new bool[clipCount];
        pt_clipLooping = new bool[n][];         pt_clipLooping[0] = clipLooping;           for (int t = 1; t < n; t++) pt_clipLooping[t] = new bool[clipCount];
        pt_clipElapsed = new float[n][];        pt_clipElapsed[0] = clipElapsed;           for (int t = 1; t < n; t++) pt_clipElapsed[t] = new float[clipCount];
        pt_triggerPlaying = new bool[n][];      pt_triggerPlaying[0] = triggerPlaying;     for (int t = 1; t < n; t++) pt_triggerPlaying[t] = new bool[triggerDefCount];
        pt_timelineTime = new float[n];         pt_timelineTime[0] = timelineTime;
        pt_previousRegionBlockState = new int[n][]; pt_previousRegionBlockState[0] = previousRegionBlockState; for (int t = 1; t < n; t++) pt_previousRegionBlockState[t] = new int[regionCount];
        pt_extRideActive = new bool[n];         pt_extRideActive[0] = extRideActive;
        pt_extCompletelyMuted = new bool[n];    pt_extCompletelyMuted[0] = extCompletelyMuted;
        pt_extFadeDisabled = new bool[n];       pt_extFadeDisabled[0] = extFadeDisabled;
        pt_extRideActiveTime = new float[n];    pt_extRideActiveTime[0] = extRideActiveTime;
        pt_wasActionPressed = new bool[n];      pt_wasActionPressed[0] = wasActionPressed;
        pt_promptShown = new bool[n];           pt_promptShown[0] = promptShown;
        pt_prevExtArmState = new int[n];        pt_prevExtArmState[0] = prevExtArmState;
        pt_duckingActive = new bool[n];         pt_duckingActive[0] = duckingActive;
        pt_duckTimer = new float[n];            pt_duckTimer[0] = duckTimer;
        pt_armedWait = new int[n];              pt_armedWait[0] = armedWait;
        pt_waitPrevIn = new bool[n][];          pt_waitPrevIn[0] = waitPrevIn;             for (int t = 1; t < n; t++) pt_waitPrevIn[t] = new bool[regionCount];
        pt_waitPlayed = new bool[n][];          pt_waitPlayed[0] = waitPlayed;             for (int t = 1; t < n; t++) pt_waitPlayed[t] = new bool[regionCount];
        pt_waitStopTime = new float[n][];       pt_waitStopTime[0] = waitStopTime;         for (int t = 1; t < n; t++) pt_waitStopTime[t] = new float[regionCount];
        pt_waitFading = new bool[n];            pt_waitFading[0] = waitFading;
        pt_waitGain = new float[n];             pt_waitGain[0] = waitGain;
        pt_waitTarget = new float[n];           pt_waitTarget[0] = waitTarget;
        pt_waitRate = new float[n];             pt_waitRate[0] = waitRate;
        pt_waitFadeRegion = new int[n];         pt_waitFadeRegion[0] = waitFadeRegion;
    }

    // Memory reuse
    private Vector3f tempPos = new Vector3f();
    private Matrix4x4f carMat = new Matrix4x4f();
    private Matrix4x4f offsetMat = new Matrix4x4f();

    // =========================================================================
    // INITIALIZATION
    // =========================================================================
    public bool onInit() {
        coaster = sim.getCoasterForEntityId(getParentEntityId());
        if (coaster == null) return false;

        // Reset extinction to defaults (parsed overrides may overwrite below)
        setExtinctionDefaults();

        // Load the binary timeline (config/timeline.bin). This also reads the
        // target train index ("trainIndex") so the script can select the train
        // for this timeline without recompiling.
        if (!loadTimelineBinary()) {
            System.err.println("Error [TimelineAudio]: No timeline data found.");
            return false;
        }

        int totalTrains = coaster.getTrainCount();
        if (totalTrains < 1) totalTrains = 1;
        trainCount = totalTrains;
        if (!allTrains && (trainIndex < 0 || trainIndex >= totalTrains)) {
            System.err.println("Error [TimelineAudio]: Invalid train index (" + trainIndex + "). Total trains: " + totalTrains);
            return false;
        }

        train = coaster.getTrainAt(allTrains ? 0 : trainIndex);
        if (train == null) return false;
        carCount = train.getCarCount();

        System.out.println("OK [TimelineAudio]: Timeline loaded. " + clipCount + " clips, " + regionCount + " cues, " + triggerDefCount + " triggers. Duration: " + timelineDuration + "s." + (allTrains ? " ALL-TRAINS mode." : ""));

        // Resolve container regions (which clips each one owns)
        buildRegionContainers();

        // Resolve follow-action targets (region names -> indices)
        buildFollowRefs();

        // Get Block references for block-launch regions
        loadRegionBlockReferences();

        // Register track triggers for trigger-launch regions
        registerRegionTriggers();

        // Register track triggers from the coaster
        registerTrackTriggers();

        // Set up extinction references
        initExtinction();

        // Allocate per-train state now that every shared array exists; train 0
        // reuses the arrays built above, the others get fresh copies.
        allocPerTrainState();
        activeTrain = -1;

        // Per-clip fire state for the extra trains (train 0 done by buildRegionContainers)
        for (int t = 1; t < trainCount; t++) {
            pt_regionContainedFired[t] = new bool[regionCount][];
            for (int i = 0; i < regionCount; i++) {
                int len = (regionContainedClips[i] != null) ? regionContainedClips[i].length : 0;
                pt_regionContainedFired[t][i] = new bool[len];
            }
            // Seed block state so extra trains don't fire spuriously on frame 1.
            for (int i = 0; i < regionCount; i++) pt_previousRegionBlockState[t][i] = previousRegionBlockState[i];
            // Seed scalar state from train 0 so every train starts equal.
            pt_timelineTime[t] = 0.0f;
            pt_extRideActive[t] = extRideActive;
            pt_extCompletelyMuted[t] = extCompletelyMuted;
            pt_extFadeDisabled[t] = extFadeDisabled;
            pt_extRideActiveTime[t] = extRideActiveTime;
            pt_wasActionPressed[t] = wasActionPressed;
            pt_promptShown[t] = promptShown;
            pt_duckingActive[t] = duckingActive;
            pt_duckTimer[t] = duckTimer;
            pt_waitFading[t] = waitFading;
            pt_waitGain[t] = waitGain;
            pt_waitTarget[t] = waitTarget;
            pt_waitRate[t] = waitRate;
            pt_waitFadeRegion[t] = waitFadeRegion;
            pt_armedWait[t] = armedWait;
            pt_prevExtArmState[t] = prevExtArmState;
        }

        // Pre-load audio for every train
        pt_clipSounds = new StaticSound[trainCount][][];
        pt_triggerSounds = new StaticSound[trainCount][][];
        pt_regionSounds = new StaticSound[trainCount][][];
        pt_regionSoundsB = new StaticSound[trainCount][][];
        pt_confirmSounds = new StaticSound[trainCount][];
        pt_regionWaitSounds = new StaticSound[trainCount][][];
        int firstLoad = allTrains ? 0 : trainIndex;
        int lastLoad = allTrains ? trainCount : trainIndex + 1;
        for (int t = firstLoad; t < lastLoad; t++) {
            setActiveTrain(t);
            pt_clipSounds[t] = new StaticSound[clipCount][carCount];
            pt_triggerSounds[t] = new StaticSound[triggerDefCount][carCount];
            pt_regionSounds[t] = new StaticSound[regionCount][carCount];
            pt_regionSoundsB[t] = new StaticSound[regionCount][carCount];
            pt_confirmSounds[t] = new StaticSound[carCount];
            pt_regionWaitSounds[t] = new StaticSound[regionCount][carCount];
            clipSounds = pt_clipSounds[t];
            triggerSounds = pt_triggerSounds[t];
            regionSounds = pt_regionSounds[t];
            regionSoundsB = pt_regionSoundsB[t];
            confirmSounds = pt_confirmSounds[t];
            regionWaitSounds = pt_regionWaitSounds[t];
            loadClipAudio();
            loadTriggerAudio();
            loadRegionAudio();
            loadConfirmSound();
            loadRegionWaitSounds();
        }
        setActiveTrain(allTrains ? 0 : trainIndex);

        // Reset timeline. The clock only drives time-launched regions; clips
        // (the DAW editing layer) are never played in-game.
        timelineTime = 0.0f;
        timelineRunning = true;

        return true;
    }

    // =========================================================================
    // LOAD TIMELINE BINARY
    // =========================================================================
    private bool loadTimelineBinary() {
        byte[] data = Tools.loadBinaryFile(TIMELINE_FILE_BIN);
        if (data == null) return false;
        return parseTimelineBinary(data);
    }

    private bool parseTimelineBinary(byte[] data) {
        if (data.length < HEADER_SIZE) return false;

        // Validate magic "RNRC"
        if (data[0] != (byte)'R' || data[1] != (byte)'N' || data[2] != (byte)'R' || data[3] != (byte)'C') {
            System.out.println("Warning [TimelineAudio]: timeline.bin invalid magic.");
            return false;
        }

        int version = data[4] & 0xFF;
        if (version != 1 && version != 2 && version != 3 && version != 4 && version != 5 && version != 6 && version != 7) {
            System.out.println("Warning [TimelineAudio]: unsupported version " + version);
            return false;
        }
        fileVersion = version;

        int trackCount    = data[5] & 0xFF;
        clipCount         = readUint16(data, 6);
        regionCount       = readUint16(data, 8);
        triggerDefCount   = readUint16(data, 10);
        timelineDuration  = readFloat32(data, 14);

        // Clamp to caps
        if (clipCount > MAX_CLIPS) clipCount = MAX_CLIPS;
        if (triggerDefCount > MAX_TRIGGERS) triggerDefCount = MAX_TRIGGERS;
        if (regionCount > MAX_REGIONS) regionCount = MAX_REGIONS;

        // Allocate arrays
        clipTrackIndex  = new int[clipCount];
        clipStartTime   = new float[clipCount];
        clipDuration    = new float[clipCount];
        clipPlayMode    = new int[clipCount];
        clipVolume      = new float[clipCount];
        clipFilename    = new String[clipCount];
        clipPlaying     = new bool[clipCount];
        clipLooping     = new bool[clipCount];
        clipElapsed     = new float[clipCount];

        regionName        = new String[regionCount];
        regionStart       = new float[regionCount];
        regionEnd         = new float[regionCount];
        regionLaunchBy    = new int[regionCount];
        regionBlockName   = new String[regionCount];
        regionStateValue  = new int[regionCount];
        regionTrackTrigger= new String[regionCount];
        regionFilename    = new String[regionCount];
        regionPlayMode    = new int[regionCount];
        regionRole        = new int[regionCount];
        regionVolume      = new float[regionCount];
        regionFadeTime    = new float[regionCount];
        regionPlaying     = new bool[regionCount];
        regionFiredTime   = new bool[regionCount];
        regionGain        = new float[regionCount];
        regionTargetGain  = new float[regionCount];
        regionFadeRate    = new float[regionCount];
        regionFadeOut     = new bool[regionCount];
        regionStopFade        = new bool[regionCount];
        regionStopFadeFrom    = new float[regionCount];
        regionStopFadeElapsed = new float[regionCount];
        regionStopFadeDur     = new float[regionCount];
        regionDuck        = new float[regionCount];
        regionDuckTarget  = new float[regionCount];
        regionDuckRate    = new float[regionCount];

        regionWaitFile      = new String[regionCount];
        regionWaitStop      = new float[regionCount];
        regionWaitFadeIn    = new float[regionCount];
        regionWaitFadeOut   = new float[regionCount];
        regionWaitRideFadeOut = new float[regionCount];
        regionWaitState     = new int[regionCount];
        regionWaitOnce      = new bool[regionCount];
        regionHoldZone      = new bool[regionCount];
        regionRestoreRide   = new bool[regionCount];
        regionFollowName    = new String[regionCount];
        regionFollow        = new int[regionCount];
        regionLoopCrossfade = new float[regionCount];
        regionLoopPos       = new float[regionCount];
        regionLoopXF        = new float[regionCount];
        regionLoopActiveB   = new bool[regionCount];
        waitPrevIn          = new bool[regionCount];
        waitPlayed          = new bool[regionCount];
        waitStopTime        = new float[regionCount];
        for (int i = 0; i < regionCount; i++) {
            regionFollowName[i] = "";
            regionFollow[i]     = -1;
            regionLoopCrossfade[i] = 0.0f;
            regionLoopPos[i]       = 0.0f;
            regionLoopXF[i]        = 0.0f;
            regionLoopActiveB[i]   = false;
        }

        triggerDefName     = new String[triggerDefCount];
        triggerDefFilename = new String[triggerDefCount];
        triggerDefPlayMode = new int[triggerDefCount];
        triggerDefVolume   = new float[triggerDefCount];
        triggerPlaying     = new bool[triggerDefCount];

        int off = HEADER_SIZE;
        if (fileVersion >= 4) {
            int dirLen = readUint16(data, 18);
            if (dirLen < 0) dirLen = 0;
            if (dirLen > 64000) dirLen = 64000;
            if (dirLen > 0) {
                audioDir = readUtf8(data, 24, dirLen);
            } else {
                audioDir = "";
            }
            off = 24 + dirLen;
        }

        // Read clips
        for (int i = 0; i < clipCount; i++) {
            clipTrackIndex[i] = data[off] & 0xFF; off++;
            clipStartTime[i]  = readFloat32(data, off); off += 4;
            if (fileVersion >= 3) {
                clipDuration[i]   = readFloat32(data, off); off += 4;
            } else {
                clipDuration[i]   = 120.0f;
            }
            clipPlayMode[i]   = data[off] & 0xFF; off++;
            clipVolume[i]     = readFloat32(data, off); off += 4;
            int nameLen       = data[off] & 0xFF; off++;
            clipFilename[i]   = readUtf8(data, off, nameLen); off += nameLen;
        }

        // Read regions
        for (int i = 0; i < regionCount; i++) {
            int nameLen     = data[off] & 0xFF; off++;
            regionName[i]   = readUtf8(data, off, nameLen); off += nameLen;
            regionLaunchBy[i] = data[off] & 0xFF; off++;
            int blLen       = data[off] & 0xFF; off++;
            regionBlockName[i] = readUtf8(data, off, blLen); off += blLen;
            regionStateValue[i] = (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8); off += 2;
            int tLen        = data[off] & 0xFF; off++;
            regionTrackTrigger[i] = readUtf8(data, off, tLen); off += tLen;
            int fLen        = data[off] & 0xFF; off++;
            regionFilename[i] = readUtf8(data, off, fLen); off += fLen;
            regionPlayMode[i] = data[off] & 0xFF; off++;
            regionRole[i]     = (fileVersion >= 2) ? (data[off] & 0xFF) : 0; if (fileVersion >= 2) off++;
            regionVolume[i]   = readFloat32(data, off); off += 4;
            regionFadeTime[i] = readFloat32(data, off); off += 4;
            regionStart[i]    = readFloat32(data, off); off += 4;
            regionEnd[i]      = readFloat32(data, off); off += 4;
            regionPlaying[i]  = false;
            regionFiredTime[i]= false;
            regionGain[i]     = 0.0f;
            regionTargetGain[i] = 0.0f;
            regionFadeRate[i] = 0.0f;
            regionDuck[i]       = 1.0f;
            regionDuckTarget[i] = 1.0f;
            regionDuckRate[i]   = 0.0f;

            // v5+: optional per-region wait-music spec (flag byte; bit0 = wait).
            // v6+: bit1 = a follow-action string naming the region to start when
            // this one finishes.
            if (fileVersion >= 5) {
                int regionFlags = data[off] & 0xFF; off++;
                regionHoldZone[i] = (regionFlags & 1) != 0;    // bit0 = hold-music zone
                regionWaitOnce[i] = (regionFlags & 4) != 0;    // bit2 = hold plays once
                regionRestoreRide[i] = (regionFlags & 8) != 0; // bit3 = restore ride on departure
                if ((regionFlags & 1) != 0) {
                    int wl = data[off] & 0xFF; off++;
                    regionWaitFile[i] = readUtf8(data, off, wl); off += wl;
                    regionWaitStop[i] = readFloat32(data, off); off += 4;
                    regionWaitFadeIn[i] = readFloat32(data, off); off += 4;
                    regionWaitFadeOut[i] = readFloat32(data, off); off += 4;
                    regionWaitRideFadeOut[i] = readFloat32(data, off); off += 4;
                    regionWaitState[i] = readUint16(data, off); off += 2;
                } else {
                    regionWaitFile[i] = "";
                }
                if ((regionFlags & 2) != 0) {
                    int fl = data[off] & 0xFF; off++;
                    regionFollowName[i] = readUtf8(data, off, fl); off += fl;
                } else {
                    regionFollowName[i] = "";
                }
                // v7+: loop crossfade time (seconds; 0 = off)
                if (fileVersion >= 7) {
                    regionLoopCrossfade[i] = readFloat32(data, off); off += 4;
                }
            }
        }

        // Read triggers
        for (int i = 0; i < triggerDefCount; i++) {
            int nameLen       = data[off] & 0xFF; off++;
            triggerDefName[i] = readUtf8(data, off, nameLen); off += nameLen;
            int fileLen       = data[off] & 0xFF; off++;
            triggerDefFilename[i] = readUtf8(data, off, fileLen); off += fileLen;
            triggerDefPlayMode[i] = data[off] & 0xFF; off++;
            triggerDefVolume[i]   = readFloat32(data, off); off += 4;
        }

        // Optional extinction footer (length stored in reserved field at offset 12)
        int extLen = readUint16(data, 12);
        if (extLen > 0 && data.length >= off + 1) {
            parseExtinctionBinary(data, off, extLen);
        }

        System.out.println("Info [TimelineAudio]: Binary timeline loaded (" + data.length + " bytes).");
        return true;
    }

    // =========================================================================
    // AUDIO PATH RESOLUTION
    // =========================================================================
    // Resolves a bare filename against the timeline's AudioDir, when that dir is
    // a relative subfolder (e.g. "Audio"). NL2's loadFromFile only accepts paths
    // relative to the script classpath, so absolute drive/UNC dirs are skipped
    // and the bare filename is used instead.
    private String resolveAudioPath(String fn) {
        if (fn == null || fn.length() == 0) return fn;
        String d = audioDir;
        if (d == null || d.length() == 0) return fn;
        if (d.startsWith("/") || d.startsWith("\\") ||
            (d.length() >= 2 && d.charAt(1) == ':')) {
            return fn;
        }
        if (d.endsWith("/") || d.endsWith("\\")) return d + fn;
        return d + "/" + fn;
    }

    // Load a sound through the NL2 resource system (declared in the
    // .nl2script description file). Resource loading does not require a
    // compile-time constant path, so it avoids the package-manager warning
    // that loadFromFile emits for paths read from the timeline data file.
    private StaticSound loadSound(String fn, int flags) {
        if (fn == null || fn.length() == 0) return null;
        String path = resolveAudioPath(fn);
        ResourcePath rp = getResourcePathForId(path);
        if (rp == null) return null;
        return StaticSound.loadFromResource(rp, flags);
    }

    // =========================================================================
    // LOAD ALL CLIP AUDIO FILES (lazy: only load unique filenames once)
    // =========================================================================
    private void loadClipAudio() {
        // clipSounds is allocated per train by onInit() before this is called.

        // Track which filenames we've already loaded to save memory
        String[] loadedFiles = new String[clipCount];
        int loadedCount = 0;
        StaticSound[][] loadedSounds = new StaticSound[clipCount][carCount];

        for (int i = 0; i < clipCount; i++) {
            String fn = clipFilename[i];
            if (fn == null || fn.length() == 0) continue;

            // Check if already loaded
            int existingIdx = -1;
            for (int j = 0; j < loadedCount; j++) {
                if (loadedFiles[j].equals(fn)) {
                    existingIdx = j;
                    break;
                }
            }

            if (existingIdx >= 0) {
                // Reuse loaded sounds (same file for multiple clips)
                for (int c = 0; c < carCount; c++) {
                    clipSounds[i][c] = loadedSounds[existingIdx][c];
                }
            } else {
                // Load new file
                for (int c = 0; c < carCount; c++) {
                    clipSounds[i][c] = loadSound(fn, 0);
                    if (clipSounds[i][c] != null) {
                        clipSounds[i][c].setDistanceParameters(audioRefDist, audioRolloff);
                        clipSounds[i][c].setDopplerMode(false);
                        clipSounds[i][c].setEnvironmentMode(StaticSound.E_ENVMODE_SAME_AS_LISTENER);
                    }
                }
                if (loadedCount < loadedFiles.length) {
                    loadedFiles[loadedCount] = fn;
                    for (int c = 0; c < carCount; c++) {
                        loadedSounds[loadedCount][c] = clipSounds[i][c];
                    }
                    loadedCount++;
                }
            }
        }
    }

    // =========================================================================
    // LOAD ALL TRIGGER AUDIO FILES
    // =========================================================================
    private void loadTriggerAudio() {
        // triggerSounds is allocated per train by onInit() before this is called.

        String[] loadedFiles = new String[triggerDefCount];
        int loadedCount = 0;
        StaticSound[][] loadedSounds = new StaticSound[triggerDefCount][carCount];

        for (int i = 0; i < triggerDefCount; i++) {
            String fn = triggerDefFilename[i];
            if (fn == null || fn.length() == 0) continue;

            int existingIdx = -1;
            for (int j = 0; j < loadedCount; j++) {
                if (loadedFiles[j].equals(fn)) {
                    existingIdx = j;
                    break;
                }
            }

            if (existingIdx >= 0) {
                for (int c = 0; c < carCount; c++) {
                    triggerSounds[i][c] = loadedSounds[existingIdx][c];
                }
            } else {
                for (int c = 0; c < carCount; c++) {
                    triggerSounds[i][c] = loadSound(fn, 0);
                    if (triggerSounds[i][c] != null) {
                        triggerSounds[i][c].setDistanceParameters(audioRefDist, audioRolloff);
                        triggerSounds[i][c].setDopplerMode(false);
                        triggerSounds[i][c].setEnvironmentMode(StaticSound.E_ENVMODE_SAME_AS_LISTENER);
                    }
                }
                if (loadedCount < loadedFiles.length) {
                    loadedFiles[loadedCount] = fn;
                    for (int c = 0; c < carCount; c++) {
                        loadedSounds[loadedCount][c] = triggerSounds[i][c];
                    }
                    loadedCount++;
                }
            }
        }
    }

    // =========================================================================
    // LOAD ALL REGION AUDIO FILES
    // =========================================================================
    private void loadRegionAudio() {
        // regionSounds / regionSoundsB are allocated per train by onInit().

        String[] loadedFiles = new String[regionCount];
        int loadedCount = 0;
        StaticSound[][] loadedSounds = new StaticSound[regionCount][carCount];

        for (int i = 0; i < regionCount; i++) {
            String fn = regionFilename[i];
            if (fn == null || fn.length() == 0) continue;
            if (fn.equals(CONT_CLIP_MARKER)) continue; // container: clips carry the audio

            int existingIdx = -1;
            for (int j = 0; j < loadedCount; j++) {
                if (loadedFiles[j].equals(fn)) {
                    existingIdx = j;
                    break;
                }
            }

            if (existingIdx >= 0) {
                for (int c = 0; c < carCount; c++) {
                    regionSounds[i][c] = loadedSounds[existingIdx][c];
                }
            } else {
                for (int c = 0; c < carCount; c++) {
                    regionSounds[i][c] = loadSound(fn, 0);
                    if (regionSounds[i][c] != null) {
                        regionSounds[i][c].setDistanceParameters(audioRefDist, audioRolloff);
                        regionSounds[i][c].setDopplerMode(false);
                        regionSounds[i][c].setEnvironmentMode(StaticSound.E_ENVMODE_SAME_AS_LISTENER);
                    }
                }
                if (loadedCount < loadedFiles.length) {
                    loadedFiles[loadedCount] = fn;
                    for (int c = 0; c < carCount; c++) {
                        loadedSounds[loadedCount][c] = regionSounds[i][c];
                    }
                    loadedCount++;
                }
            }
        }

        // Crossfade looping needs a second, independent handle of the same file.
        for (int i = 0; i < regionCount; i++) {
            if (regionPlayMode[i] != 1) continue;
            if (regionLoopCrossfade[i] <= 0.01f) continue;
            String fn = regionFilename[i];
            if (fn == null || fn.length() == 0) continue;
            if (fn.equals(CONT_CLIP_MARKER)) continue;
            for (int c = 0; c < carCount; c++) {
                regionSoundsB[i][c] = loadSound(fn, 0);
                if (regionSoundsB[i][c] != null) {
                    regionSoundsB[i][c].setDistanceParameters(audioRefDist, audioRolloff);
                    regionSoundsB[i][c].setDopplerMode(false);
                    regionSoundsB[i][c].setEnvironmentMode(StaticSound.E_ENVMODE_SAME_AS_LISTENER);
                }
            }
            if (regionSoundsB[i][0] != null) {
                System.out.println("Info [TimelineAudio]: Crossfade loop enabled for region '" + regionName[i] + "' (" + regionLoopCrossfade[i] + "s).");
            }
        }
    }

    // =========================================================================
    // REGISTER TRACK TRIGGERS FROM COASTER
    // Each trigger definition name corresponds to a TrackTrigger on the coaster
    // named "audio_trigger_<name>" (e.g. audio_trigger_dispatcher, audio_trigger_wildline)
    // =========================================================================
    private void registerTrackTriggers() {
        trackTriggers = new TrackTrigger[triggerDefCount];
        triggerCount = 0;

        for (int i = 0; i < triggerDefCount; i++) {
            String trigName = triggerDefName[i];
            if (trigName == null || trigName.length() == 0) continue;

            // Try "audio_trigger_<name>" on the coaster
            TrackTrigger tt = coaster.getTrackTrigger("audio_trigger_" + trigName);
            if (tt != null) {
                if (!isTriggerRegistered(tt)) tt.addTrackTriggerListener(this);
                trackTriggers[i] = tt;
                triggerCount++;
                System.out.println("Info [TimelineAudio]: Trigger '" + trigName + "' registered.");
            } else {
                // Try just the name directly
                tt = coaster.getTrackTrigger(trigName);
                if (tt != null) {
                    if (!isTriggerRegistered(tt)) tt.addTrackTriggerListener(this);
                    trackTriggers[i] = tt;
                    triggerCount++;
                    System.out.println("Info [TimelineAudio]: Trigger '" + trigName + "' registered (direct).");
                } else {
                    System.out.println("Warning [TimelineAudio]: TrackTrigger '" + trigName + "' not found on coaster.");
                    trackTriggers[i] = null;
                }
            }
        }
    }

    // =========================================================================
    // EXTINCTION SETTINGS - DEFAULTS
    // =========================================================================
    private void setExtinctionDefaults() {
        extEnabled        = false;
        extArmBlock       = "Zone1";
        extArmState       = 4;
        extStoppedSeconds = 15.0f;
        extFadeSeconds    = 3.5f;
        extMuteLock       = true;
        extRestoreBlock   = "Zone7";
        extRestoreState   = 8;
        extRestoreTrigger = "";
        extRestoreRegion  = "";
        extScriptedBlocks = false;
        extRestoreRegionRef = -1;
        duckDepth         = 0.75f;
        duckAttack        = 0.35f;
        duckRelease       = 0.80f;
        extConfirmFile    = "";
        extDisableFadeTrigger = "";
        extFadeDisabled   = false;
        duckingActive     = false;
        duckTimer         = 0.0f;
        armedWait         = -1;
        waitFadeRegion    = -1;
        waitFading        = false;
        waitGain          = 0.0f;
        waitTarget        = 0.0f;
        waitRate          = 0.0f;
        confirmSounds     = null;
        disableFadeTriggerRef = null;
        audioRefDist     = 3.0f;
        audioRolloff     = 5.0f;
        trainIndex       = 0;
        extArmBlockRef      = null;
        extRestoreBlockRef  = null;
        extRestoreTriggerRef= null;
        prevExtArmState     = -1;
        extRideActive       = false;
        extCompletelyMuted  = false;
        extRideActiveTime   = 0.0f;
        wasActionPressed    = false;
        promptShown         = false;
    }

    // =========================================================================
    // INIT EXTINCTION + RELATED BEHAVIORS (ducking, wait music, confirm, disable-fade)
    // Resolves block/trigger references and pre-loads auxiliary audio.
    // =========================================================================
    private void initExtinction() {
        // Extinction references
        if (extEnabled) {
            if (extArmBlock != null && extArmBlock.length() > 0) {
                extArmBlockRef = coaster.getBlock(extArmBlock);
                if (extArmBlockRef != null) {
                    prevExtArmState = blockStateValue(extArmBlockRef, extArmState);
                } else {
                    extArmBlockRef = null;
                }
            }
            if (extRestoreBlock != null && extRestoreBlock.length() > 0) {
                extRestoreBlockRef = coaster.getBlock(extRestoreBlock);
            }
            if (extRestoreTrigger != null && extRestoreTrigger.length() > 0) {
                extRestoreTriggerRef = coaster.getTrackTrigger(extRestoreTrigger);
                if (extRestoreTriggerRef == null) extRestoreTriggerRef = coaster.getTrackTrigger("audio_trigger_" + extRestoreTrigger);
                if (extRestoreTriggerRef != null) extRestoreTriggerRef.addTrackTriggerListener(this);
            }
            if (extRestoreRegion != null && extRestoreRegion.length() > 0) {
                extRestoreRegionRef = findRegionIndex(extRestoreRegion);
                if (extRestoreRegionRef < 0) {
                    System.out.println("Warning [TimelineAudio]: Restore region '" + extRestoreRegion + "' not found.");
                }
            }
        }

        // Disable-fade trigger (works independently of extinction lock)
        if (extDisableFadeTrigger != null && extDisableFadeTrigger.length() > 0) {
            disableFadeTriggerRef = coaster.getTrackTrigger(extDisableFadeTrigger);
            if (disableFadeTriggerRef == null) disableFadeTriggerRef = coaster.getTrackTrigger("audio_trigger_" + extDisableFadeTrigger);
            if (disableFadeTriggerRef != null) disableFadeTriggerRef.addTrackTriggerListener(this);
        }

        // Confirm beep and station-wait music are loaded per train in onInit()
        // (after the per-train sound arrays are allocated), so nothing to do here.

        System.out.println("Info [TimelineAudio]: Behaviors ready. Extinction=" + (extEnabled ? "on" : "off")
            + ", ducking (depth " + duckDepth + ", att " + duckAttack + "s, rel " + duckRelease + "s)"
            + ", confirm='" + extConfirmFile + "'"
            + ", disableFade='" + extDisableFadeTrigger + "'.");
    }

    // =========================================================================
    // LOAD CONFIRM BEEP (Action-restore confirmation) PER CAR
    // =========================================================================
    private void loadConfirmSound() {
        // confirmSounds is allocated per train by onInit() before this is called.
        if (extConfirmFile == null || extConfirmFile.length() == 0) return;   // no restore sound configured
        for (int c = 0; c < carCount; c++) {
            confirmSounds[c] = loadSound(extConfirmFile, 0);
            if (confirmSounds[c] != null) {
                confirmSounds[c].setDistanceParameters(audioRefDist, audioRolloff);
                confirmSounds[c].setDopplerMode(false);
                confirmSounds[c].setEnvironmentMode(StaticSound.E_ENVMODE_SAME_AS_LISTENER);
            }
        }
        if (confirmSounds[0] == null) {
            System.out.println("Warning [TimelineAudio]: Confirm file '" + extConfirmFile + "' not found.");
        }
    }

    // =========================================================================
    // LOAD STATION WAIT MUSIC PER REGION (zone-neutral, replaces Zone 5)
    // Only block-launch regions with a waitFile participate.
    // =========================================================================
    private void loadRegionWaitSounds() {
        // regionWaitSounds is allocated per train by onInit() before this is called.

        String[] loadedFiles = new String[regionCount];
        int loadedCount = 0;
        StaticSound[][] loadedSounds = new StaticSound[regionCount][carCount];

        for (int i = 0; i < regionCount; i++) {
            if (regionLaunchBy[i] != 1) continue;
            String fn = regionWaitFile[i];
            if (fn == null || fn.length() == 0) continue;
            if (coaster.getBlock(regionBlockName[i]) == null) continue;

            int existingIdx = -1;
            for (int j = 0; j < loadedCount; j++) {
                if (loadedFiles[j].equals(fn)) {
                    existingIdx = j;
                    break;
                }
            }

            if (existingIdx >= 0) {
                for (int c = 0; c < carCount; c++) {
                    regionWaitSounds[i][c] = loadedSounds[existingIdx][c];
                }
            } else {
                for (int c = 0; c < carCount; c++) {
                    regionWaitSounds[i][c] = loadSound(fn, 0);
                    if (regionWaitSounds[i][c] != null) {
                        regionWaitSounds[i][c].setDistanceParameters(audioRefDist, audioRolloff);
                        regionWaitSounds[i][c].setDopplerMode(false);
                        regionWaitSounds[i][c].setEnvironmentMode(StaticSound.E_ENVMODE_SAME_AS_LISTENER);
                        regionWaitSounds[i][c].setGain(0.0f);
                    }
                }
                if (regionWaitSounds[i][0] == null) {
                    System.out.println("Warning [TimelineAudio]: Wait file '" + fn + "' for region '" + regionName[i] + "' not found.");
                }
                if (loadedCount < loadedFiles.length) {
                    loadedFiles[loadedCount] = fn;
                    for (int c = 0; c < carCount; c++) {
                        loadedSounds[loadedCount][c] = regionWaitSounds[i][c];
                    }
                    loadedCount++;
                }
            }
        }
    }

    // =========================================================================
    // PARSE EXTINCTION FROM BINARY (optional footer appended after triggers)
    // Returns the new offset, or the given offset untouched if no record.
    // =========================================================================
    private int parseExtinctionBinary(byte[] data, int off, int extLen) {
        if (extLen <= 0) return off;
        extEnabled = (data[off] & 0xFF) != 0; off++;
        int bl = data[off] & 0xFF; off++;
        if (bl <= extLen) { extArmBlock = readUtf8(data, off, bl); off += bl; } else return off;
        extArmState = readUint16(data, off); off += 2;
        extStoppedSeconds = readFloat32(data, off); off += 4;
        extFadeSeconds    = readFloat32(data, off); off += 4;
        extMuteLock = (data[off] & 0xFF) != 0; off++;
        int rl = data[off] & 0xFF; off++;
        if (rl <= extLen) { extRestoreBlock = readUtf8(data, off, rl); off += rl; } else return off;
        extRestoreState = readUint16(data, off); off += 2;
        int tl = data[off] & 0xFF; off++;
        if (tl <= extLen) { extRestoreTrigger = readUtf8(data, off, tl); off += tl; } else return off;

        // Extended behaviors (all optional; short records fall back and leave defaults)
        if (off + 12 > data.length) return off;
        duckDepth  = readFloat32(data, off); off += 4;
        duckAttack = readFloat32(data, off); off += 4;
        duckRelease= readFloat32(data, off); off += 4;
        if (off + 1 > data.length) return off;
        int cl = data[off] & 0xFF; off++;
        if (cl <= extLen && off + cl <= data.length) { extConfirmFile = readUtf8(data, off, cl); off += cl; }
        if (off + 1 > data.length) return off;
        int dl = data[off] & 0xFF; off++;
        if (dl <= extLen && off + dl <= data.length) { extDisableFadeTrigger = readUtf8(data, off, dl); off += dl; }
        // Audio distance settings (appended last; fall back to defaults if absent)
        if (off + 8 <= data.length) {
            audioRefDist = readFloat32(data, off); off += 4;
            audioRolloff  = readFloat32(data, off); off += 4;
        }
        // Train index (appended after the audio settings; fall back to 0)
        if (off + 2 <= data.length) {
            trainIndex = readUint16(data, off); off += 2;
        }
        // Trailing editor-only strings: scriptName (1-byte length + utf8) then
        // the restore region (1-byte length + utf8). Older files end earlier.
        if (off + 1 <= data.length) {
            int sn = data[off] & 0xFF; off++;
            if (sn > 0 && off + sn <= data.length) off += sn;
        }
        if (off + 1 <= data.length) {
            int rrl = data[off] & 0xFF; off++;
            if (rrl > 0 && off + rrl <= data.length) {
                extRestoreRegion = readUtf8(data, off, rrl); off += rrl;
            }
        }
        // Scripted-vs-normal block-state mode (1 = scripted). Optional tail.
        if (off + 1 <= data.length) {
            extScriptedBlocks = (data[off] & 0xFF) != 0; off++;
        }
        // All-trains mode (1 = run the timeline independently for every train).
        if (off + 1 <= data.length) {
            allTrains = (data[off] & 0xFF) != 0; off++;
        }
        return off;
    }

    // =========================================================================
    // FRAME UPDATE FOR ALL BEHAVIOR STATE MACHINES
    // =========================================================================
    private void updateBehaviors(float tick) {
        updateDuckingTimers(tick);
        updateWaitMusic(tick);
        updateExtinction(tick);
    }

    // =========================================================================
    // DUCKING - wildlines (role 2) duck music (role 1) regions.
    // Mirrors OnboardAudio1: on a voice, duck the music for the voice length,
    // then release. Re-attach if another voice fires while already ducking.
    // =========================================================================
    private void startDucking(float voiceLength) {
        if (extCompletelyMuted) return;
        duckMusic(duckAttack);
        duckingActive = true;
        duckTimer = voiceLength;
    }

    private void updateDuckingTimers(float tick) {
        if (!duckingActive) return;
        duckTimer -= tick;
        if (duckTimer <= 0.0f) {
            duckingActive = false;
            unduckMusic(duckRelease);
        }
    }

    private void duckMusic(float duration) {
        for (int i = 0; i < regionCount; i++) {
            if (regionRole[i] != 1 && regionRole[i] != 3) continue;   // Music + Hold music
            if (!regionPlaying[i]) continue;
            if (regionSounds[i][0] == null) continue;
            if (regionFadeOut[i]) continue;          // don't fight an active fade-out stop
            float target = duckDepth;
            if (target < 0.001f) target = 0.0f;
            regionDuckTarget[i] = target;
            if (duration <= 0.01f) {
                regionDuck[i] = target;
                regionDuckRate[i] = 0.0f;
            } else {
                regionDuckRate[i] = Math.abs(regionDuck[i] - target) / duration;
            }
        }
    }

    private void unduckMusic(float duration) {
        for (int i = 0; i < regionCount; i++) {
            if (regionRole[i] != 1 && regionRole[i] != 3) continue;   // Music + Hold music
            if (!regionPlaying[i]) continue;
            if (regionSounds[i][0] == null) continue;
            if (regionFadeOut[i]) continue;
            regionDuckTarget[i] = 1.0f;
            if (duration <= 0.01f) {
                regionDuck[i] = 1.0f;
                regionDuckRate[i] = 0.0f;
            } else {
                regionDuckRate[i] = Math.abs(1.0f - regionDuck[i]) / duration;
            }
        }
    }

    // =========================================================================
    // STATION WAIT MUSIC (per block region, zone-neutral) - replaces the old
    // global "Zone 5" idle music. Any block-launched region with a waitFile
    // becomes a wait zone: stopped there for its waitStop seconds -> fade ride
    // music out and fade the wait channel in; on departure, fade it back out.
    // =========================================================================
    private void updateWaitMusic(float tick) {
        if (armedWait < 0) return;
        if (armedWait >= regionCount) { armedWait = -1; return; }
        if (!regionHoldZone[armedWait]) { armedWait = -1; return; }
        if (regionBlocks[armedWait] == null) { armedWait = -1; return; }
        if (extCompletelyMuted) return;

        Train tIn = regionBlocks[armedWait].getSection().getTrainOnSection();
        bool inZone = (tIn == train);

        if (inZone) {
            if (Math.abs(train.getSpeed()) < 0.1f) {
                waitStopTime[armedWait] += tick;
                if (waitStopTime[armedWait] > regionWaitStop[armedWait] && !waitPlayed[armedWait]) {
                    waitPlayed[armedWait] = true;
                    System.out.println("Info [TimelineAudio]: Hold zone '" + regionName[armedWait] + "': stopped -> ride music out, hold music in.");
                    fadeMusicRole(regionWaitRideFadeOut[armedWait]);
                    startHoldMusic(armedWait, regionWaitFadeIn[armedWait]);
                }
            } else {
                waitStopTime[armedWait] = 0.0f;
            }
            waitPrevIn[armedWait] = true;
        } else {
            if (waitPrevIn[armedWait]) {
                waitPrevIn[armedWait] = false;
                waitPlayed[armedWait] = false;
                waitStopTime[armedWait] = 0.0f;
                stopHoldMusic(armedWait, regionWaitFadeOut[armedWait]);
                if (regionRestoreRide[armedWait]) restoreRideMusic(regionWaitFadeOut[armedWait]);
            }
        }
    }

    // Start the hold-music cue (its contained clips) with a fade-in.
    private void startHoldMusic(int i, float fadeIn) {
        if (regionPlaying[i]) return;
        if (fadeIn <= 0.01f) fadeIn = 0.01f;
        regionPlaying[i] = true;
        regionElapsed[i] = 0.0f;
        regionFadeOut[i] = false;
        regionGain[i] = 0.0f;
        regionTargetGain[i] = regionVolume[i];
        regionFadeRate[i] = regionVolume[i] / fadeIn;
        stopContainedClips(i);
        resetContainedFired(i);
        fireContained(i);
    }

    // Fade the hold-music cue out over fadeOut, then stop it.
    private void stopHoldMusic(int i, float fadeOut) {
        if (!regionPlaying[i]) return;
        if (fadeOut <= 0.01f) fadeOut = 0.01f;
        float from = regionGain[i] > 0.0001f ? regionGain[i] : regionVolume[i];
        regionTargetGain[i] = 0.0f;
        regionFadeRate[i] = from / fadeOut;
        regionFadeOut[i] = true;
    }

    // Bring the ride music (role 1) back up after the hold zone ends.
    private void restoreRideMusic(float duration) {
        if (duration <= 0.01f) duration = 0.01f;
        for (int i = 0; i < regionCount; i++) {
            if (regionRole[i] != 1) continue;
            if (!regionPlaying[i]) continue;
            if (regionFadeOut[i]) continue;
            regionTargetGain[i] = regionVolume[i];
            regionFadeRate[i] = Math.abs(regionVolume[i] - regionGain[i]) / duration;
        }
    }

    // Arm a wait zone when its block region fires for our train. If another
    // wait zone was active, fade its channel out using its own fade-out time.
    private void armWaitRegion(int i) {
        if (armedWait == i) return;
        if (armedWait >= 0 && armedWait < regionCount && regionPlaying[armedWait]) {
            stopHoldMusic(armedWait, regionWaitFadeOut[armedWait]);
        }
        armedWait = i;
        waitPrevIn[i] = false;
        waitPlayed[i] = false;
        waitStopTime[i] = 0.0f;
    }

    private void fadeWaitIn(int region, float duration) {
        if (duration <= 0.01f) duration = 0.01f;
        waitFadeRegion = region;
        waitTarget = 1.0f;
        waitRate = 1.0f / duration;
        waitFading = true;
        for (int c = 0; c < carCount; c++) {
            if (regionWaitSounds[region][c] != null) {
                regionWaitSounds[region][c].setGain(0.0f);
                if (regionWaitOnce[region]) regionWaitSounds[region][c].play();
                else regionWaitSounds[region][c].playLoop();
            }
        }
    }

    private void fadeWaitOut(int region, float duration) {
        if (duration <= 0.01f) duration = 0.01f;
        waitFadeRegion = region;
        waitTarget = 0.0f;
        waitRate = (waitGain > 0.001f) ? waitGain / duration : 0.0f;
        waitFading = true;
    }

    private void updateWaitFades(float tick) {
        if (!waitFading) return;
        if (waitFadeRegion < 0) { waitFading = false; return; }
        if (waitGain < waitTarget) {
            waitGain += waitRate * tick;
            if (waitGain >= waitTarget) {
                waitGain = waitTarget;
                waitFading = false;
            }
        } else if (waitGain > waitTarget) {
            waitGain -= waitRate * tick;
            if (waitGain <= waitTarget) {
                waitGain = waitTarget;
                waitFading = false;
                if (waitGain <= 0.001f) {
                    for (int c = 0; c < carCount; c++) {
                        if (regionWaitSounds[waitFadeRegion][c] != null) regionWaitSounds[waitFadeRegion][c].stop();
                    }
                }
            }
        }
        for (int c = 0; c < carCount; c++) {
            if (regionWaitSounds[waitFadeRegion][c] != null) regionWaitSounds[waitFadeRegion][c].setGain(waitGain);
        }
    }

    private void fadeMusicRole(float duration) {
        for (int i = 0; i < regionCount; i++) {
            if (regionRole[i] != 1) continue;
            if (!regionPlaying[i]) continue;
            if (regionSounds[i][0] == null) continue;
            if (regionFadeOut[i]) continue;
            regionTargetGain[i] = 0.0f;
            if (duration <= 0.01f) {
                regionGain[i] = 0.0f;
                regionFadeRate[i] = 0.0f;
            } else {
                regionFadeRate[i] = regionGain[i] / duration;
            }
        }
    }

    // =========================================================================
    // CONFIRM BEEP (played when the Action key restores audio)
    // =========================================================================
    private void playConfirmBeep() {
        if (confirmSounds == null || confirmSounds[0] == null) return;
        for (int c = 0; c < carCount; c++) {
            if (confirmSounds[c] != null) {
                confirmSounds[c].setGain(1.0f);
                confirmSounds[c].stop();
                confirmSounds[c].play();
            }
        }
    }

    // =========================================================================
    // FRAME UPDATE FOR EXTINCTION (stop-triggered fade-out state machine)
    // =========================================================================
    private void updateExtinction(float tick) {
        if (!extEnabled) return;

        // 1. Arm/disarm on launch block transition
        if (extArmBlockRef != null) {
            int st = blockStateValue(extArmBlockRef, extArmState);
            int armTarget = blockTargetState(extArmState);
            if (st == armTarget && prevExtArmState != armTarget) {
                Train tIn = extArmBlockRef.getSection().getTrainOnSection();
                if (tIn == train && !extCompletelyMuted) {
                    extRideActive = true;
                    extRideActiveTime = 0.0f;
                    extFadeDisabled = false;
                    System.out.println("Info [TimelineAudio]: Extinction armed (block '" + extArmBlock + "' -> state " + extArmState + ").");
                }
            }
            prevExtArmState = st;
        }

        // 2. If muted, freeze the ride state but still allow the Action-key
        //    restore in the station (restore block). This must run even while
        //    muted, otherwise the player can never un-mute.
        if (extCompletelyMuted) {
            extRideActive = false;
            checkExtinctionRestore();
            return;
        }

        // 3. Stopped-time accumulation -> fade out all playing regions, then mute-lock.
        //    The timer only counts while the train is actually stopped (speed < 0.1),
        //    so a brief slow moment mid-ride never fires it. Skipped while fading is
        //    disabled (audio_disable_fade) or a voice is ducking.
        if (extRideActive && !extFadeDisabled && !duckingActive) {
            if (Math.abs(train.getSpeed()) < 0.1f) {
                extRideActiveTime += tick;
                if (extRideActiveTime > extStoppedSeconds) {
                    System.out.println("Info [TimelineAudio]: Extinction fired: fade out playing regions (" + extFadeSeconds + "s).");
                    stopAllPlayingRegions(extFadeSeconds);
                    extRideActive = false;
                    if (extMuteLock) {
                        extCompletelyMuted = true;
                        System.out.println("Info [TimelineAudio]: Audio muted. Press Action in station to restore.");
                    }
                }
            } else {
                extRideActiveTime = 0.0f;
            }
        }

        // 4. Manual restore via Action key in the restore block (station)
        checkExtinctionRestore();
    }

    // =========================================================================
    // ACTION-KEY RESTORE - MIRRORS OnboardAudio1 SECTION 7
    // =========================================================================
    private void checkExtinctionRestore() {
        bool isActionPressed = Button.isPressed(Button.ACTION);
        if (extRestoreBlockRef != null && blockStateValue(extRestoreBlockRef, extRestoreState) == blockTargetState(extRestoreState)) {
            Train tIn = extRestoreBlockRef.getSection().getTrainOnSection();
            if (tIn == train && Math.abs(train.getSpeed()) < 0.1f) {
                train.getCarMatrix(carCount - 1, carMat);
                offsetMat.initTrans(0.0f, 1.0f, 3.0f);
                carMat.multRight(offsetMat);
                carMat.getTrans(tempPos);

                Vector3f camPos = new Vector3f();
                sim.getViewPos(camPos);
                camPos.sub(tempPos);

                if (camPos.length() < 4.0f) {
                    if (!promptShown && (extCompletelyMuted || !extRideActive)) {
                        System.out.println("Info [TimelineAudio]: Near the rear. Press Action key to restore audio.");
                        promptShown = true;
                    }
                    if (isActionPressed && !wasActionPressed) {
                        System.out.println("Info [TimelineAudio]: Action OK: audio restored.");
                        extCompletelyMuted = false;
                        extRideActive = false;
                        extFadeDisabled = false;
                        playConfirmBeep();
                        restartStationLoops();
                        if (extRestoreRegionRef >= 0 && extRestoreRegionRef < regionCount) {
                            System.out.println("Info [TimelineAudio]: Action restore starts region '" + regionName[extRestoreRegionRef] + "'.");
                            startRegionAudio(extRestoreRegionRef);
                        }
                        // Special-event regions (launchBy = restore) fire on restore.
                        for (int ri = 0; ri < regionCount; ri++) {
                            if (regionLaunchBy[ri] == 3) {
                                System.out.println("Info [TimelineAudio]: Action restore fires special event '" + regionName[ri] + "'.");
                                startRegionAudio(ri);
                            }
                        }
                    }
                } else {
                    promptShown = false;
                }
            } else {
                promptShown = false;
            }
        } else {
            promptShown = false;
        }
        wasActionPressed = isActionPressed;
    }

    // =========================================================================
    // RESTART LOOPING REGIONS (used by the Action-key restore)
    // =========================================================================
    private void restartStationLoops() {
        for (int i = 0; i < regionCount; i++) {
            if (regionPlayMode[i] != 1) continue;   // only loop-mode regions
            if (regionSounds[i][0] == null) continue;
            if (regionLaunchBy[i] != 2) continue;   // only trigger-launched loops
            startRegionAudio(i);
        }
    }

    // =========================================================================
    // REGISTER TRACK TRIGGERS FOR TRIGGER-LAUNCH REGIONS
    // Each region with launchBy=trigger references a TrackTrigger by name.
    // =========================================================================
    private void registerRegionTriggers() {
        regionTriggers = new TrackTrigger[regionCount];

        for (int i = 0; i < regionCount; i++) {
            regionTriggers[i] = null;
            if (regionLaunchBy[i] != 2) continue;
            String trigName = regionTrackTrigger[i];
            if (trigName == null || trigName.length() == 0) continue;

            TrackTrigger tt = coaster.getTrackTrigger("audio_trigger_" + trigName);
            if (tt == null) tt = coaster.getTrackTrigger(trigName);
            if (tt != null) {
                // Register the listener only ONCE per trigger, so several
                // regions sharing the same trigger name don't cause
                // onTrainEntering to fire multiple times for one event.
                if (!isTriggerRegistered(tt)) {
                    tt.addTrackTriggerListener(this);
                }
                regionTriggers[i] = tt;
                System.out.println("Info [TimelineAudio]: Region '" + regionName[i] + "' trigger '" + trigName + "' registered.");
            } else {
                System.out.println("Warning [TimelineAudio]: TrackTrigger '" + trigName + "' not found for region '" + regionName[i] + "'.");
            }
        }
    }

    // Returns true if the given trigger is already wired to this listener by an
    // earlier trigger-launch region or trigger definition (avoids duplicate
    // registration and double onTrainEntering calls).
    private bool isTriggerRegistered(TrackTrigger tt) {
        if (regionTriggers != null) {
            for (int i = 0; i < regionCount; i++) {
                if (regionTriggers[i] == tt) return true;
            }
        }
        if (trackTriggers != null) {
            for (int i = 0; i < triggerDefCount; i++) {
                if (trackTriggers[i] == tt) return true;
            }
        }
        return false;
    }

    // =========================================================================
    // LOAD BLOCK REFERENCES FOR BLOCK-LAUNCH REGIONS
    // Each region with launchBy=block references an NL2 Block by name. The state
    // source depends on the scripted/normal mode (see blockStateValue).
    // =========================================================================
    private void loadRegionBlockReferences() {
        regionBlocks = new Block[regionCount];
        previousRegionBlockState = new int[regionCount];

        for (int i = 0; i < regionCount; i++) {
            if (regionLaunchBy[i] != 1 || regionBlockName[i] == null || regionBlockName[i].length() == 0) {
                regionBlocks[i] = null;
                previousRegionBlockState[i] = -1;
                continue;
            }

            regionBlocks[i] = coaster.getBlock(regionBlockName[i]);
            if (regionBlocks[i] != null) {
                previousRegionBlockState[i] = blockStateValue(regionBlocks[i], regionStateValue[i]);
                System.out.println("Info [TimelineAudio]: Region '" + regionName[i] + "' -> block '" + regionBlockName[i] + "' found (" + (extScriptedBlocks ? "scripted" : "normal") + " mode).");
            } else {
                previousRegionBlockState[i] = -1;
                System.out.println("Warning [TimelineAudio]: Block '" + regionBlockName[i] + "' not found for region '" + regionName[i] + "'.");
            }
        }
    }

    // =========================================================================
    // FRAME UPDATE
    // =========================================================================
    public void onNextFrame(float tick) {
        if (allTrains) {
            for (int t = 0; t < trainCount; t++) {
                setActiveTrain(t);
                updateTrainFrame(tick);
            }
        } else {
            setActiveTrain(trainIndex);
            updateTrainFrame(tick);
        }
    }

    // Per-train frame update (positions + behavior + playback).
    private void updateTrainFrame(float tick) {
        // 1. Update 3D positions for all sounds
        for (int i = 0; i < carCount; i++) {
            train.getCarMatrix(i, carMat);
            carMat.getTrans(tempPos);

            // Position all clip sounds
            for (int ci = 0; ci < clipCount; ci++) {
                if (clipPlaying[ci] && clipSounds[ci][i] != null) {
                    clipSounds[ci][i].setPosition(tempPos.x, tempPos.y, tempPos.z);
                }
            }
            // Position all trigger sounds
            for (int ti = 0; ti < triggerDefCount; ti++) {
                if (triggerPlaying[ti] && triggerSounds[ti][i] != null) {
                    triggerSounds[ti][i].setPosition(tempPos.x, tempPos.y, tempPos.z);
                }
            }
            // Position all region sounds (both crossfade handles)
            for (int ri = 0; ri < regionCount; ri++) {
                if (!regionPlaying[ri]) continue;
                if (regionSounds[ri][i] != null) {
                    regionSounds[ri][i].setPosition(tempPos.x, tempPos.y, tempPos.z);
                }
                if (regionSoundsB != null && regionSoundsB[ri] != null && regionSoundsB[ri][i] != null) {
                    regionSoundsB[ri][i].setPosition(tempPos.x, tempPos.y, tempPos.z);
                }
            }
            // Position wait music (active wait zone's channel)
            if (waitFadeRegion >= 0 && waitFadeRegion < regionCount &&
                regionWaitSounds != null && regionWaitSounds[waitFadeRegion] != null &&
                regionWaitSounds[waitFadeRegion][i] != null && waitGain > 0.0001f) {
                regionWaitSounds[waitFadeRegion][i].setPosition(tempPos.x, tempPos.y, tempPos.z);
            }
            // Position confirm sound
            if (confirmSounds != null && confirmSounds[i] != null) {
                confirmSounds[i].setPosition(tempPos.x, tempPos.y, tempPos.z);
            }
        }

        // 1b. Update behavior state machines every frame (even if timeline idle)
        updateBehaviors(tick);

        if (!timelineRunning) return;

        // 2. Advance timeline clock. Clips (the DAW editing layer) are never
        // played in-game; the clock only drives time-launched regions.
        timelineTime += tick;

        // 3. Advance container-region playheads (schedule their contained clips)
        updateRegionClips(tick);

        // 3a. Stop trimmed once-clips when they reach their editor length
        updateClipPlayback(tick);

        // 3b. Advance once-region completion (fires follow actions)
        updateRegionCompletion(tick);

        // 4. Check time-launched regions (audio fires when clock reaches region start)
        for (int ri = 0; ri < regionCount; ri++) {
            if (regionLaunchBy[ri] == 0 && !regionPlaying[ri] && !regionFiredTime[ri]) {
                if (timelineTime >= regionStart[ri]) {
                    startRegionAudio(ri);
                    regionFiredTime[ri] = true;
                }
            }
        }

        // 4b. Advance crossfade loops (before fades apply their gains)
        updateLoopCrossfades(tick);

        // 5. Poll block states for block-launch regions (fire on state transitions)
        pollRegionBlocks(tick);
    }

    // =========================================================================
    // BLOCK-STATE MODE
    // Scripted operation mode uses custom states (Block.getState); unscripted
    // (normal) mode uses the built-in states from Block.getNormalModeState. The
    // stored state value is an index in normal mode; map it to the constant.
    // =========================================================================
    private int blockStateValue(Block b, int idx) {
        if (b == null) return -1;
        if (extScriptedBlocks) return b.getState();
        if (idx <= 6) return b.getNormalModeState(Block.PROTOCOL_V1);
        return sectionCondition(b, idx) ? 1 : 0;
    }

    private int blockTargetState(int idx) {
        if (extScriptedBlocks) return idx;
        if (idx <= 6) return normalModeStateForIndex(idx);
        return 1;
    }

    private int normalModeStateForIndex(int idx) {
        if (idx == 0) return Block.STATE_IDLE;
        if (idx == 1) return Block.STATE_OCCUPIED;
        if (idx == 2) return Block.STATE_IN_STATION;
        if (idx == 3) return Block.STATE_APPROACHING_FWD;
        if (idx == 4) return Block.STATE_APPROACHING_BWD;
        if (idx == 5) return Block.STATE_OFFLINE;
        if (idx == 6) return Block.STATE_FULL_MANUAL_MODE;
        return Block.STATE_OCCUPIED;
    }

    // Extra normal-mode conditions derived from the section (Section class),
    // usable on unscripted coasters. Returns true when the condition holds.
    private bool sectionCondition(Block b, int idx) {
        Section s = b.getSection();
        if (s == null) return false;
        if (idx == 7)  return s.isTrainOnSection();
        if (idx == 8)  return s.isTrainBeforeCenterOfSection();
        if (idx == 9)  return s.isTrainBehindCenterOfSection();
        if (idx == 10) return s.isTrainBeforeBrakeTrigger();
        if (idx == 11) return s.isTrainBehindBrakeTrigger();
        if (idx == 12) return s.isTrainBeforeLiftTrigger();
        if (idx == 13) return s.isTrainBehindLiftTrigger();
        if (idx == 14) return s.isBrakesOn();
        if (idx == 15) return s.isStationWaitingForClearBlock();
        if (idx == 16) return s.isStationWaitingForAdvance();
        if (idx == 17) return s.getStationGateState() > 0.5f;
        if (idx == 18) return s.getStationPlatformState() > 0.5f;
        if (idx == 19) return s.getLiftCurrentSpeed() > 0.1;
        if (idx == 20) return s.getTransportCurrentSpeed() > 0.1;
        return false;
    }

    // =========================================================================
    // POLL BLOCK STATES AND FIRE AUDIO ON TRANSITIONS (block-launch regions)
    // =========================================================================
    private void pollRegionBlocks(float tick) {
        for (int i = 0; i < regionCount; i++) {
            if (regionLaunchBy[i] != 1) continue;
            if (regionBlocks[i] == null) continue;

            int currentState = blockStateValue(regionBlocks[i], regionStateValue[i]);
            int targetState  = blockTargetState(regionStateValue[i]);
            int prevState = previousRegionBlockState[i];

            // Detect transition: state changed to the target value
            if (currentState == targetState && prevState != targetState) {
                // Guard: only fire when the specific train on the block is OUR train
                Train tIn = regionBlocks[i].getSection().getTrainOnSection();
                if (tIn == train) {
                    System.out.println("Info [TimelineAudio]: Cue '" + regionName[i] + "' fired: block '" + regionBlockName[i] + "' state " + prevState + " -> " + currentState);
                    if (regionHoldZone[i]) {
                        // Hold-music zone: arm and wait for the train to stop; do not play yet.
                        armWaitRegion(i);
                    } else {
                        startRegionAudio(i);
                    }
                } else {
                    System.out.println("Info [TimelineAudio]: Region '" + regionName[i] + "' skipped: block '" + regionBlockName[i] + "' state " + prevState + " -> " + currentState + " (different train)");
                }
            }

            previousRegionBlockState[i] = currentState;
        }

        // Update fading for all active regions
        updateRegionFades(tick);
        updateStopFades(tick);
    }

    // =========================================================================
    // START REGION AUDIO
    // =========================================================================
    private void startRegionAudio(int i) {
        if (i < 0 || i >= regionCount) return;
        regionElapsed[i] = 0.0f;
        regionStopFade[i] = false;
        if (regionFilename[i] == null || regionFilename[i].length() == 0) {
            // Empty filename = stop command: fade out and stop all looping regions.
            regionPlaying[i] = false;
            stopAllLoopingRegions(regionFadeTime[i]);
            return;
        }

        // Extinction mute lock: while muted, nothing new starts.
        if (extCompletelyMuted) return;

        // Container region: play the clips inside its range instead of a file.
        if (regionIsContainer[i]) {
            regionFadeOut[i] = false;
            regionPlaying[i] = true;
            regionElapsed[i] = 0.0f;
            stopContainedClips(i);
            resetContainedFired(i);
            float cFade = regionFadeTime[i];
            regionGain[i] = 0.0f;
            regionTargetGain[i] = regionVolume[i];
            regionFadeRate[i] = (cFade > 0.01f) ? regionVolume[i] / cFade : 0.0f;
            if (cFade <= 0.01f) regionGain[i] = regionVolume[i];
            fireContained(i);
            if (regionRole[i] == 2) {
                startDucking(regionVoiceLength(i));
            }
            if (regionRole[i] == 1 && duckingActive) {
                regionDuck[i] = (duckDepth < 0.001f) ? 0.0f : duckDepth;
                regionDuckTarget[i] = regionDuck[i];
                regionDuckRate[i] = 0.0f;
            }
            return;
        }

        regionFadeOut[i] = false;
        float vol = regionVolume[i];

        if (regionPlayMode[i] == 1) {
            if (isCrossfadeLoop(i)) {
                // Crossfade loop: start handle A and reset the crossfade state.
                regionLoopActiveB[i] = false;
                regionLoopPos[i] = 0.0f;
                regionLoopXF[i] = 0.0f;
                for (int c = 0; c < carCount; c++) {
                    if (regionSoundsB[i][c] != null) regionSoundsB[i][c].stop();
                    if (regionSounds[i][c] != null) {
                        regionSounds[i][c].setGain(0.0f);
                        regionSounds[i][c].play();
                    }
                }
            } else {
                // Loop mode
                for (int c = 0; c < carCount; c++) {
                    if (regionSounds[i][c] != null) {
                        regionSounds[i][c].setGain(vol);
                        regionSounds[i][c].playLoop();
                    }
                }
            }
        } else {
            // Once mode
            for (int c = 0; c < carCount; c++) {
                if (regionSounds[i][c] != null) {
                    regionSounds[i][c].setGain(vol);
                    regionSounds[i][c].play();
                }
            }
        }

        regionPlaying[i] = true;

        // Voice regions (role 2) duck the music (role 1) while they play
        if (regionRole[i] == 2 && regionSounds[i][0] != null) {
            float voiceLen = (float) regionSounds[i][0].getLength();
            if (voiceLen <= 0.05f) voiceLen = 4.0f;
            startDucking(voiceLen);
        }

        // Set up fade
        float fadeTime = regionFadeTime[i];
        if (fadeTime > 0.0f) {
            regionGain[i] = 0.0f;
            regionTargetGain[i] = vol;
            regionFadeRate[i] = vol / fadeTime;
            for (int c = 0; c < carCount; c++) {
                if (regionSounds[i][c] != null) {
                    regionSounds[i][c].setGain(0.0f);
                }
            }
        } else {
            regionGain[i] = vol;
            regionTargetGain[i] = vol;
            regionFadeRate[i] = 0.0f;
        }

        // If a voice is currently ducking, start this music region already ducked
        // (its fade-in then runs scaled by the duck, not clobbered by it).
        if (regionRole[i] == 1 && duckingActive) {
            regionDuck[i] = (duckDepth < 0.001f) ? 0.0f : duckDepth;
            regionDuckTarget[i] = regionDuck[i];
            regionDuckRate[i] = 0.0f;
        }
    }

    // =========================================================================
    // CONTAINER REGIONS (clip scheduling)
    // A container region (filename == CONT_CLIP_MARKER) owns every clip whose
    // start lies inside [Start, End). On launch its playhead starts at 0 and
    // fires each contained clip when the offset is reached (clips at the same
    // offset play together = layered audio). Loop-mode containers wrap the
    // playhead over the region span and replay the contained sequence.
    // =========================================================================
    private void updateRegionClips(float tick) {
        for (int i = 0; i < regionCount; i++) {
            if (!regionPlaying[i]) continue;
            if (!regionIsContainer[i]) continue;
            if (regionContainedClips[i] == null) continue;

            regionElapsed[i] += tick;
            float span = regionEnd[i] - regionStart[i];

            if (regionPlayMode[i] == 1) {
                if (regionFadeOut[i]) continue;   // fade-out in progress: don't restart the sequence
                // Looping container: wrap the playhead and replay the sequence.
                if (span > 0.01f && regionElapsed[i] >= span) {
                    while (regionElapsed[i] >= span) regionElapsed[i] -= span;
                    stopContainedClips(i);
                    resetContainedFired(i);
                }
            } else if (span > 0.01f && regionElapsed[i] >= span) {
                // Once container: the contained sequence has finished. If a fade
                // (stop command or extinction) is still running, let it finish
                // instead of cutting the audio short.
                if (regionFadeOut[i] && regionGain[i] > 0.001f) {
                    continue;
                }
                if (regionFadeOut[i]) {
                    stopRegionAudio(i);
                } else if (regionHoldZone[i]) {
                    stopRegionAudio(i);   // hold zone: no follow-actions on completion
                } else {
                    completeRegion(i);
                }
                continue;
            }
            fireContained(i);
        }
    }

    private void fireContained(int i) {
        if (regionContainedClips[i] == null) return;
        for (int k = 0; k < regionContainedClips[i].length; k++) {
            if (regionContainedFired[i][k]) continue;
            int ci = regionContainedClips[i][k];
            float offset = clipStartTime[ci] - regionStart[i];
            if (regionElapsed[i] >= offset - 0.001f) {
                startContainedClip(i, k);
            }
        }
    }

    private void startContainedClip(int i, int k) {
        int ci = regionContainedClips[i][k];
        regionContainedFired[i][k] = true;
        float base = regionGain[i] > 0.001f ? regionGain[i] : regionTargetGain[i];
        float g = clipVolume[ci] * base * regionDuck[i];
        clipPlaying[ci] = true;
        clipElapsed[ci] = 0.0f;
        if (clipPlayMode[ci] == 1) {
            clipLooping[ci] = true;
            for (int c = 0; c < carCount; c++) {
                if (clipSounds[ci][c] != null) {
                    clipSounds[ci][c].setGain(g);
                    clipSounds[ci][c].playLoop();
                }
            }
        } else {
            clipLooping[ci] = false;
            for (int c = 0; c < carCount; c++) {
                if (clipSounds[ci][c] != null) {
                    clipSounds[ci][c].setGain(g);
                    clipSounds[ci][c].play();
                }
            }
        }
    }

    private void resetContainedFired(int i) {
        if (regionContainedClips[i] == null) return;
        for (int k = 0; k < regionContainedClips[i].length; k++) {
            regionContainedFired[i][k] = false;
        }
    }

    private void stopContainedClips(int i) {
        if (regionContainedClips[i] == null) return;
        for (int k = 0; k < regionContainedClips[i].length; k++) {
            stopClip(regionContainedClips[i][k]);
        }
    }

    private float regionVoiceLength(int i) {
        float maxd = 0.0f;
        if (regionContainedClips[i] != null) {
            for (int k = 0; k < regionContainedClips[i].length; k++) {
                if (clipDuration[regionContainedClips[i][k]] > maxd) {
                    maxd = clipDuration[regionContainedClips[i][k]];
                }
            }
        }
        if (maxd > 0.05f) return maxd;
        float span = regionEnd[i] - regionStart[i];
        if (span > 0.05f) return span;
        return 4.0f;
    }

    private void buildRegionContainers() {
        regionIsContainer = new bool[regionCount];
        regionContainedClips = new int[regionCount][];
        regionContainedFired = new bool[regionCount][];
        regionElapsed = new float[regionCount];
        for (int i = 0; i < regionCount; i++) {
            regionElapsed[i] = 0.0f;
            regionIsContainer[i] = regionFilename[i] != null && regionFilename[i].equals(CONT_CLIP_MARKER);
            if (!regionIsContainer[i]) {
                regionContainedClips[i] = null;
                regionContainedFired[i] = null;
                continue;
            }
            int n = 0;
            for (int ci = 0; ci < clipCount; ci++) {
                if (clipStartTime[ci] >= regionStart[i] - 0.001f && clipStartTime[ci] < regionEnd[i]) n++;
            }
            regionContainedClips[i] = new int[n];
            regionContainedFired[i] = new bool[n];
            int idx = 0;
            for (int ci = 0; ci < clipCount; ci++) {
                if (clipStartTime[ci] >= regionStart[i] - 0.001f && clipStartTime[ci] < regionEnd[i]) {
                    regionContainedClips[i][idx] = ci;
                    regionContainedFired[i][idx] = false;
                    idx++;
                }
            }
            System.out.println("Info [TimelineAudio]: Container region '" + regionName[i] + "': " + n + " contained clip(s).");
        }
    }

    // =========================================================================
    // REGION FOLLOW ACTIONS
    // A region can name another region to start automatically when it finishes:
    // once-mode region completes, its clip sequence completes, or its audio
    // runs to the end of the file. Looping regions never "finish" on their own.
    // =========================================================================
    private int findRegionIndex(String name) {
        if (name == null || name.length() == 0) return -1;
        if (regionName == null) return -1;
        for (int i = 0; i < regionCount; i++) {
            if (regionName[i] != null && regionName[i].equals(name)) return i;
        }
        return -1;
    }

    private void buildFollowRefs() {
        if (regionFollowName == null) return;
        for (int i = 0; i < regionCount; i++) {
            String fn = regionFollowName[i];
            if (fn == null || fn.length() == 0) continue;
            int start = 0;
            while (start <= fn.length()) {
                int sep = fn.indexOf(';', start);
                if (sep < 0) sep = fn.length();
                String nm = fn.substring(start, sep).trim();
                if (nm.length() > 0) {
                    int t = findRegionIndex(nm);
                    if (t < 0 || t == i) {
                        System.out.println("Warning [TimelineAudio]: Region '" + regionName[i] + "' follow target '" + nm + "' not found (or self).");
                    } else {
                        System.out.println("Info [TimelineAudio]: Region '" + regionName[i] + "' will start '" + regionName[t] + "' when it finishes.");
                    }
                }
                if (sep >= fn.length()) break;
                start = sep + 1;
            }
        }
    }

    // Called when a once-mode region has finished playing naturally. Starts every
    // follow target (a ';'-separated list of region names) and stops the finished
    // region. Natural completion only — manual stop commands (fade-outs, stop
    // regions, extinction) never trigger a follow.
    private void completeRegion(int i) {
        String names = (regionFollowName != null && i >= 0 && i < regionCount) ? regionFollowName[i] : "";
        if (names != null && names.length() > 0) {
            int start = 0;
            while (start <= names.length()) {
                int sep = names.indexOf(';', start);
                if (sep < 0) sep = names.length();
                String nm = names.substring(start, sep).trim();
                if (nm.length() > 0) {
                    int t = findRegionIndex(nm);
                    if (t >= 0 && t != i) {
                        System.out.println("Info [TimelineAudio]: Region '" + regionName[i] + "' finished -> follow '" + regionName[t] + "'.");
                        startRegionAudio(t);
                    } else {
                        System.out.println("Warning [TimelineAudio]: Region '" + regionName[i] + "' follow target '" + nm + "' not found (or self).");
                    }
                }
                if (sep >= names.length()) break;
                start = sep + 1;
            }
        }
        stopRegionAudio(i);
    }

    // Advances per-region elapsed time for once-mode (non-container) regions and
    // fires the follow action when the sound reaches its natural end.
    private void updateRegionCompletion(float tick) {
        for (int i = 0; i < regionCount; i++) {
            if (!regionPlaying[i]) continue;
            if (regionPlayMode[i] != 0) continue;     // once-mode only
            if (regionFadeOut[i]) continue;           // stopped by a stop command
            if (regionIsContainer[i]) continue;       // handled by updateRegionClips
            if (regionSounds[i] == null || regionSounds[i][0] == null) continue;
            float len = (float) regionSounds[i][0].getLength();
            if (len <= 0.05f) continue;
            regionElapsed[i] += tick;
            if (regionElapsed[i] >= len) {
                completeRegion(i);
            }
        }
    }

    // =========================================================================
    // STOP ALL LOOPING REGIONS (fade-out command)
    // Fades every currently-looping region to silence over fadeTime, then stops.
    // Matches OnboardAudio1's behavior: loops start via trigger, fade out on a
    // block state transition.
    // =========================================================================
    private void stopAllLoopingRegions(float fadeTime) {
        // fadeTime 0 = instant stop; > 0 fades the loops out over that time.
        bool instant = (fadeTime <= 0.01f);
        int faded = 0;
        for (int j = 0; j < regionCount; j++) {
            if (!regionPlaying[j]) continue;

            bool isLoopRegion = (regionPlayMode[j] == 1);
            bool hasLoopClip = false;
            if (regionIsContainer[j] && regionContainedClips[j] != null) {
                for (int k = 0; k < regionContainedClips[j].length; k++) {
                    int ci = regionContainedClips[j][k];
                    if (clipPlaying[ci] && clipPlayMode[ci] == 1) { hasLoopClip = true; break; }
                }
            }
            if (!isLoopRegion && !hasLoopClip) continue;

            if (instant) { stopRegionAudio(j); continue; }

            // Start from the gain actually applied right now (accounting for an
            // in-progress fade), so re-issuing a stop never jumps the volume up.
            float from = regionGain[j];
            if (regionStopFade[j] && regionStopFadeDur[j] > 0.01f) {
                float ff = regionStopFadeElapsed[j] / regionStopFadeDur[j];
                if (ff > 1.0f) ff = 1.0f;
                from = regionStopFadeFrom[j] * (1.0f - ff);
            }
            if (from <= 0.0001f) { stopRegionAudio(j); continue; }
            regionStopFade[j] = true;
            regionStopFadeFrom[j] = from;
            regionStopFadeElapsed[j] = 0.0f;
            regionStopFadeDur[j] = fadeTime;
            regionFadeOut[j]    = true;
            faded++;
            System.out.println("Info [TimelineAudio]: Stop command: fading '" + regionName[j] + "' from " + from + " over " + fadeTime + "s.");
        }
        System.out.println("Info [TimelineAudio]: Stop command fired: " + faded + " loop region(s) fading over " + fadeTime + "s.");
    }

    // =========================================================================
    // STOP ALL PLAYING REGIONS (extinction)
    // Fades every currently-playing region (once AND loop) to silence over
    // fadeTime. Mirrors OnboardAudio1's extinction, which fades CH_RIDE (a
    // one-shot) when the ride halts longer than the threshold.
    // =========================================================================
    private void stopAllPlayingRegions(float fadeTime) {
        for (int j = 0; j < regionCount; j++) {
            if (!regionPlaying[j]) continue;
            if (regionIsContainer[j]) {
                if (regionGain[j] <= 0.0001f || fadeTime <= 0.01f) {
                    stopRegionAudio(j);
                    continue;
                }
                regionTargetGain[j] = 0.0f;
                regionFadeRate[j]   = regionGain[j] / fadeTime;
                regionFadeOut[j]    = true;
                continue;
            }
            if (regionSounds[j][0] == null || regionGain[j] <= 0.0001f || fadeTime <= 0.01f) {
                stopRegionAudio(j);
                continue;
            }
            regionTargetGain[j] = 0.0f;
            regionFadeRate[j]   = regionGain[j] / fadeTime;
            regionFadeOut[j]    = true;
        }
    }

    // =========================================================================
    // STOP REGION AUDIO
    // =========================================================================
    private void stopRegionAudio(int i) {
        if (i < 0 || i >= regionCount) return;
        if (regionIsContainer[i]) {
            regionPlaying[i] = false;
            regionElapsed[i] = 0.0f;
            stopContainedClips(i);
            resetContainedFired(i);
        }
        regionPlaying[i] = false;
        regionGain[i] = 0.0f;
        regionTargetGain[i] = 0.0f;
        regionFadeRate[i] = 0.0f;
        regionFadeOut[i] = false;
        regionStopFade[i] = false;
        regionDuck[i] = 1.0f;
        regionDuckTarget[i] = 1.0f;
        regionDuckRate[i] = 0.0f;
        if (regionLoopXF != null) { regionLoopXF[i] = 0.0f; regionLoopPos[i] = 0.0f; }
        for (int c = 0; c < carCount; c++) {
            if (regionSounds[i][c] != null) {
                regionSounds[i][c].stop();
            }
            if (regionSoundsB != null && regionSoundsB[i] != null && regionSoundsB[i][c] != null) {
                regionSoundsB[i][c].stop();
            }
        }
    }

    // =========================================================================
    // CROSSFADE LOOPING
    // A loop region with a crossfade time > 0 uses two handles of the same file.
    // Near the end of the active handle the other one starts and the two gains
    // are blended across the seam, then the active handle is swapped. This makes
    // each repetition overlap the next instead of restarting abruptly.
    // =========================================================================
    private bool isCrossfadeLoop(int i) {
        if (regionPlayMode[i] != 1) return false;
        if (regionIsContainer[i]) return false;
        if (regionLoopCrossfade == null || regionLoopCrossfade[i] <= 0.01f) return false;
        if (regionSounds == null || regionSounds[i] == null || regionSounds[i][0] == null) return false;
        if (regionSoundsB == null || regionSoundsB[i] == null || regionSoundsB[i][0] == null) return false;
        float len = (float) regionSounds[i][0].getLength();
        return len > regionLoopCrossfade[i] + 0.05f;
    }

    private void startLoopHandle(int i, int which, float gain) {
        for (int c = 0; c < carCount; c++) {
            StaticSound s = (which == 0) ? regionSounds[i][c] : regionSoundsB[i][c];
            if (s != null) {
                s.setGain(gain);
                s.play();
            }
        }
    }

    // Advances each crossfade loop's playhead and manages the A/B swap.
    private void updateLoopCrossfades(float tick) {
        if (regionLoopCrossfade == null) return;
        for (int i = 0; i < regionCount; i++) {
            if (!regionPlaying[i]) continue;
            if (!isCrossfadeLoop(i)) continue;
            if (regionFadeOut[i]) continue; // stopping: don't start a new cycle

            float len = (float) regionSounds[i][0].getLength();
            float xf  = regionLoopCrossfade[i];

            regionLoopPos[i] += tick;

            if (regionLoopXF[i] <= 0.0f && regionLoopPos[i] >= len - xf) {
                int other = regionLoopActiveB[i] ? 0 : 1;
                startLoopHandle(i, other, 0.0f);
                regionLoopXF[i] = 0.0001f;
            }
            if (regionLoopXF[i] > 0.0f) {
                regionLoopXF[i] += tick;
                if (regionLoopXF[i] >= xf) {
                    regionLoopActiveB[i] = !regionLoopActiveB[i];
                    regionLoopPos[i] -= len;
                    if (regionLoopPos[i] < 0.0f) regionLoopPos[i] = 0.0f;
                    regionLoopXF[i] = 0.0f;
                }
            }
        }
    }

    // Applies the blended gains of the two crossfade handles, scaled by the
    // region's current gain (which already includes fades and ducking).
    private void applyCrossfadeGains(int i, float g) {
        float xf = regionLoopCrossfade[i];
        float blend = 0.0f;
        if (regionLoopXF[i] > 0.0f) {
            blend = regionLoopXF[i] / xf;
            if (blend > 1.0f) blend = 1.0f;
        }
        float activeGain = g * (1.0f - blend);
        float otherGain  = g * blend;
        for (int c = 0; c < carCount; c++) {
            StaticSound a = regionSounds[i][c];
            StaticSound b = regionSoundsB[i][c];
            if (regionLoopActiveB[i]) {
                if (b != null) b.setGain(activeGain);
                if (a != null) a.setGain(otherGain);
            } else {
                if (a != null) a.setGain(activeGain);
                if (b != null) b.setGain(otherGain);
            }
        }
    }

    // =========================================================================
    // UPDATE REGION FADES (called each frame)
    // =========================================================================
    private void updateRegionFades(float tick) {
        for (int i = 0; i < regionCount; i++) {
            if (!regionPlaying[i]) continue;
            if (regionStopFade[i]) continue;   // dedicated stop fade owns this region's gain
            bool xfLoop = isCrossfadeLoop(i);
            if (regionFadeRate[i] <= 0.0f && regionDuckRate[i] <= 0.0f && !xfLoop
                && regionGain[i] == regionTargetGain[i] && regionDuck[i] == regionDuckTarget[i]) continue;

            // Fade gain: region volume ramped in/out (also used by the wait-music
            // and stop/extinction fades).
            if (regionGain[i] < regionTargetGain[i]) {
                regionGain[i] += regionFadeRate[i] * tick;
                if (regionGain[i] >= regionTargetGain[i]) {
                    regionGain[i] = regionTargetGain[i];
                    regionFadeRate[i] = 0.0f;
                }
            }
            else if (regionGain[i] > regionTargetGain[i]) {
                regionGain[i] -= regionFadeRate[i] * tick;
                if (regionGain[i] <= 0.0f) {
                    regionGain[i] = 0.0f;
                    regionFadeRate[i] = 0.0f;
                    if (regionFadeOut[i]) {
                        stopRegionAudio(i);
                        continue;
                    }
                }
            }

            // Duck multiplier, animated independently of the fade so a voice
            // starting/ending never clobbers an in-progress fade.
            if (regionDuck[i] < regionDuckTarget[i]) {
                regionDuck[i] += regionDuckRate[i] * tick;
                if (regionDuck[i] >= regionDuckTarget[i]) {
                    regionDuck[i] = regionDuckTarget[i];
                    regionDuckRate[i] = 0.0f;
                }
            }
            else if (regionDuck[i] > regionDuckTarget[i]) {
                regionDuck[i] -= regionDuckRate[i] * tick;
                if (regionDuck[i] <= regionDuckTarget[i]) {
                    regionDuck[i] = regionDuckTarget[i];
                    regionDuckRate[i] = 0.0f;
                }
            }

            float g = regionGain[i] * regionDuck[i];
            if (regionIsContainer[i] && regionContainedClips[i] != null) {
                for (int k = 0; k < regionContainedClips[i].length; k++) {
                    int ci = regionContainedClips[i][k];
                    if (!clipPlaying[ci]) continue;
                    for (int c = 0; c < carCount; c++) {
                        if (clipSounds[ci][c] != null) {
                            clipSounds[ci][c].setGain(clipVolume[ci] * g);
                        }
                    }
                }
            } else if (xfLoop) {
                applyCrossfadeGains(i, g);
            } else {
                for (int c = 0; c < carCount; c++) {
                    if (regionSounds[i][c] != null) {
                        regionSounds[i][c].setGain(g);
                    }
                }
            }
        }
    }

    // =========================================================================
    // DEDICATED STOP-COMMAND FADE
    // Time-based fade of a region (or its contained clips) to silence over the
    // stop region's fade time. Independent of the normal gain ramp so nothing
    // can shorten or clobber it.
    // =========================================================================
    private void updateStopFades(float tick) {
        for (int i = 0; i < regionCount; i++) {
            if (!regionStopFade[i]) continue;
            regionStopFadeElapsed[i] += tick;
            float dur = regionStopFadeDur[i];
            float f = (dur > 0.01f) ? (regionStopFadeElapsed[i] / dur) : 1.0f;
            if (f >= 1.0f) {
                regionStopFade[i] = false;
                stopRegionAudio(i);
                continue;
            }
            float g = regionStopFadeFrom[i] * regionDuck[i] * (1.0f - f);
            if (regionIsContainer[i] && regionContainedClips[i] != null) {
                for (int k = 0; k < regionContainedClips[i].length; k++) {
                    int ci = regionContainedClips[i][k];
                    if (!clipPlaying[ci]) continue;
                    for (int c = 0; c < carCount; c++) {
                        if (clipSounds[ci][c] != null) clipSounds[ci][c].setGain(clipVolume[ci] * g);
                    }
                }
            } else if (isCrossfadeLoop(i)) {
                applyCrossfadeGains(i, g);
            } else {
                for (int c = 0; c < carCount; c++) {
                    if (regionSounds[i][c] != null) regionSounds[i][c].setGain(g);
                }
            }
        }
    }

    // =========================================================================
    // START A CLIP
    // =========================================================================
    private void startClip(int ci) {
        if (ci < 0 || ci >= clipCount) return;

        float vol = clipVolume[ci];

        if (clipPlayMode[ci] == 1) {
            // Loop mode
            clipLooping[ci] = true;
            for (int c = 0; c < carCount; c++) {
                if (clipSounds[ci][c] != null) {
                    clipSounds[ci][c].setGain(vol);
                    clipSounds[ci][c].playLoop();
                }
            }
        } else {
            // Once mode
            clipLooping[ci] = false;
            for (int c = 0; c < carCount; c++) {
                if (clipSounds[ci][c] != null) {
                    clipSounds[ci][c].setGain(vol);
                    clipSounds[ci][c].play();
                }
            }
        }
        clipPlaying[ci] = true;
        clipElapsed[ci] = 0.0f;
    }

    // =========================================================================
    // STOP A CLIP
    // =========================================================================
    private void stopClip(int ci) {
        if (ci < 0 || ci >= clipCount) return;
        clipPlaying[ci] = false;
        clipLooping[ci] = false;
        for (int c = 0; c < carCount; c++) {
            if (clipSounds[ci][c] != null) {
                clipSounds[ci][c].stop();
            }
        }
    }

    // =========================================================================
    // STOP TRIMMED ONCE-CLIPS AT THEIR EDITOR LENGTH
    // =========================================================================
    private void updateClipPlayback(float tick) {
        for (int ci = 0; ci < clipCount; ci++) {
            if (!clipPlaying[ci]) continue;
            if (clipLooping[ci]) continue;
            float natural = 0.0f;
            if (clipSounds[ci] != null && clipSounds[ci][0] != null) natural = (float) clipSounds[ci][0].getLength();
            // Only force-stop a genuinely trimmed clip (stored length shorter
            // than the file). Otherwise let the sound play out naturally.
            if (clipDuration[ci] > 0.1f && natural > 0.1f && clipDuration[ci] < natural - 0.05f) {
                clipElapsed[ci] += tick;
                if (clipElapsed[ci] >= clipDuration[ci]) {
                    System.out.println("Info [TimelineAudio]: Clip " + ci + " trimmed, stopped at " + clipDuration[ci] + "s.");
                    stopClip(ci);
                }
            }
        }
    }

    // =========================================================================
    // STOP ALL AUDIO
    // =========================================================================
    private void stopAllAudio() {
        for (int ci = 0; ci < clipCount; ci++) {
            stopClip(ci);
        }
        for (int ti = 0; ti < triggerDefCount; ti++) {
            stopTrigger(ti);
        }
        for (int ri = 0; ri < regionCount; ri++) {
            stopRegionAudio(ri);
        }
    }

    // =========================================================================
    // PLAY A TRIGGER SOUND
    // =========================================================================
    private void playTrigger(int ti) {
        if (ti < 0 || ti >= triggerDefCount) return;
        if (extCompletelyMuted) return;
        float vol = triggerDefVolume[ti];

        if (triggerDefPlayMode[ti] == 1) {
            // Loop
            for (int c = 0; c < carCount; c++) {
                if (triggerSounds[ti][c] != null) {
                    triggerSounds[ti][c].setGain(vol);
                    triggerSounds[ti][c].playLoop();
                }
            }
        } else {
            // Once
            for (int c = 0; c < carCount; c++) {
                if (triggerSounds[ti][c] != null) {
                    triggerSounds[ti][c].setGain(vol);
                    triggerSounds[ti][c].play();
                }
            }
        }
        triggerPlaying[ti] = true;
    }

    // =========================================================================
    // STOP A TRIGGER SOUND
    // =========================================================================
    private void stopTrigger(int ti) {
        if (ti < 0 || ti >= triggerDefCount) return;
        triggerPlaying[ti] = false;
        for (int c = 0; c < carCount; c++) {
            if (triggerSounds[ti][c] != null) {
                triggerSounds[ti][c].stop();
            }
        }
    }

    // =========================================================================
    // TRACK TRIGGER CALLBACK
    // =========================================================================
    public void onTrainEntering(TrackTrigger trigger, Train t) {
        int ti = trainIndexOf(t);
        if (ti < 0) return;
        // In single-train mode ignore triggers from any other train BEFORE
        // switching state, otherwise we'd switch to a train whose audio was
        // never loaded (null sound arrays) and crash on the next play call.
        if (!allTrains && ti != trainIndex) return;
        setActiveTrain(ti);

        // Disable-fade trigger: turns off extinction fading for the current ride cycle
        if (disableFadeTriggerRef != null && disableFadeTriggerRef == trigger && !extCompletelyMuted) {
            extFadeDisabled = true;
            System.out.println("Info [TimelineAudio]: Extinction fade disabled by trigger.");
            return;
        }

        // Extinction watch-end trigger: marks the end of the monitored section.
        // The train is watched for stalls from the arm block (Zone 1 launch)
        // until this trigger; once crossed, the watch ends. This does NOT
        // restore audio — restoring stays with the Action key in the station.
        if (extEnabled && extRestoreTriggerRef != null && extRestoreTriggerRef == trigger) {
            extRideActive = false;
            System.out.println("Info [TimelineAudio]: Extinction watch ended (trigger).");
            return;
        }

        // Play every matching trigger definition (a trigger can be both a
        // definition and a region launcher).
        for (int i = 0; i < triggerDefCount; i++) {
            if (trackTriggers[i] == trigger) {
                System.out.println("Info [TimelineAudio]: Trigger fired: " + triggerDefName[i]);
                playTrigger(i);
            }
        }

        // Fire EVERY trigger-launch region that uses this trigger name, so
        // several regions can share one trigger. (Previously only the first
        // matching region fired because of an early return.)
        for (int i = 0; i < regionCount; i++) {
            if (regionLaunchBy[i] == 2 && regionTriggers[i] == trigger) {
                System.out.println("Info [TimelineAudio]: Region '" + regionName[i] + "' fired by trigger.");
                startRegionAudio(i);
            }
        }
    }

    public void onTrainLeaving(TrackTrigger trigger, Train t) {
    }

    // =========================================================================
    // BINARY HELPERS
    // =========================================================================
    private static int readUint16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static float readFloat32(byte[] data, int offset) {
        int b0 = data[offset]     & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int b3 = data[offset + 3] & 0xFF;
        int bits = (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
        return Float.intBitsToFloat(bits);
    }

    private static String readUtf8(byte[] data, int offset, int length) {
        // The script engine has no charset classes, so decode UTF-8 by hand.
        char[] buf = new char[length];
        int n = 0;
        int i = 0;
        while (i < length) {
            int b = data[offset + i] & 0xFF;
            i++;
            int cp;
            int need;
            if (b < 0x80) { cp = b; need = 0; }
            else if (b >= 0xC0 && b < 0xE0) { cp = b & 0x1F; need = 1; }
            else if (b >= 0xE0 && b < 0xF0) { cp = b & 0x0F; need = 2; }
            else if (b >= 0xF0) { cp = b & 0x07; need = 3; }
            else { cp = b; need = 0; }
            for (int k = 0; k < need; k++) {
                if (i >= length) break;
                int c = data[offset + i] & 0xFF;
                i++;
                cp = (cp << 6) | (c & 0x3F);
            }
            if (cp > 0xFFFF) {
                int v = cp - 0x10000;
                buf[n] = (char) (0xD800 + (v >> 10)); n++;
                buf[n] = (char) (0xDC00 + (v & 0x3FF)); n++;
            } else {
                buf[n] = (char) cp; n++;
            }
        }
        char[] out = new char[n];
        for (int j = 0; j < n; j++) out[j] = buf[j];
        return new String(out);
    }

}
