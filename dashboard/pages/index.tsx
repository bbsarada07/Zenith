import React, { useState, useRef, useCallback, useEffect } from 'react';
import Head from 'next/head';
import { useBridgeStream } from '@/hooks/useBridgeStream';
import { ScreenCanvas } from '@/components/ScreenCanvas';
import { TelemetryPanel } from '@/components/TelemetryPanel';
import { ControlPanel } from '@/components/ControlPanel';
import { ClosedLoopExecutor } from '@/lib/engine/closedLoopExecutor';
import { NpuGroundingEngine } from '@/lib/ai/npuGroundingEngine';
import { MacroRecorder, MacroRecipe } from '@/lib/macroRecorder';
import { WakeWordListener } from '@/lib/voice/wakeWordListener';
import { ActionAuditLog, ActionStep } from '@/lib/schemas/actionSchema';

export default function DashboardPage() {
  const [hostInput, setHostInput] = useState<string>('192.168.1.3');
  const [portInput, setPortInput] = useState<number>(8080);
  const [isSettingsOpen, setIsSettingsOpen] = useState<boolean>(false);

  // Overlay Toggles
  const [showBoundingBoxes, setShowBoundingBoxes] = useState<boolean>(true);
  const [showTargetOffsets, setShowTargetOffsets] = useState<boolean>(true);
  const [enablePrivacyMasking, setEnablePrivacyMasking] = useState<boolean>(false);

  // Execution & Macro States
  const [isExecuting, setIsExecuting] = useState<boolean>(false);
  const [isRecordingMacro, setIsRecordingMacro] = useState<boolean>(false);
  const [savedMacros, setSavedMacros] = useState<MacroRecipe[]>([]);
  const [auditLogs, setAuditLogs] = useState<ActionAuditLog[]>([]);

  // Voice Wake-Word State
  const [isVoiceListening, setIsVoiceListening] = useState<boolean>(false);
  const [voiceStatusText, setVoiceStatusText] = useState<string>('Voice Standby ("Hey Zenith...")');

  const auditLogIdCounter = useRef<number>(0);

  const addAuditLog = useCallback((
    channel: 'WEBSOCKET_STREAM' | 'OFFICE_KIT_CLIPBOARD' | 'LOCAL_NPU_QNN',
    command: string,
    targetCoords: string | undefined,
    confidence: number,
    statusCode: number,
    latencyMs: number,
    status: 'SUCCESS' | 'RETRY' | 'FAILED'
  ) => {
    const newLog: ActionAuditLog = {
      id: `log_${++auditLogIdCounter.current}`,
      timestamp: new Date().toISOString().slice(11, 23),
      channel,
      command,
      targetCoords,
      confidence,
      statusCode,
      latencyMs,
      status
    };

    setAuditLogs(prev => [...prev.slice(-49), newLog]);
  }, []);

  // Bridge Stream Hook
  const {
    bridgeStatus,
    bridgeProtocol,
    fps,
    frameLatencyMs,
    npuInferenceTimeMs,
    npuUsagePercent,
    confidenceMatrix,
    executionProvider,
    heapMemoryMb,
    activeApp,
    boundingBoxes,
    latestBitmap,
    officeKitBridge,
    connect,
    disconnect,
    sendRemoteTouch,
    sendRemoteSwipe,
    sendNavAction,
    sendTextInput,
    sendCommand,
    setBridgeEndpoint
  } = useBridgeStream({
    initialHost: hostInput,
    initialPort: portInput,
    autoConnect: true,
    onAuditLog: addAuditLog
  });

  // Local NPU Grounding Engine
  const npuEngineRef = useRef<NpuGroundingEngine>(new NpuGroundingEngine());

  // Tri-Stage Self-Healing Closed Loop Executor
  const executorRef = useRef<ClosedLoopExecutor | null>(null);

  // Macro Recorder
  const macroRecorderRef = useRef<MacroRecorder>(
    new MacroRecorder((isRec) => {
      setIsRecordingMacro(isRec);
    })
  );

  // Voice Wake-Word Engine
  const voiceListenerRef = useRef<WakeWordListener | null>(null);

  // Initialize ClosedLoopExecutor & VoiceListener
  useEffect(() => {
    executorRef.current = new ClosedLoopExecutor(
      hostInput,
      portInput,
      officeKitBridge,
      npuEngineRef.current,
      sendCommand,
      {
        onStepStart: (step, idx, total) => {
          console.log(`[ClosedLoop] Step ${idx}/${total}: ${step.description}`);
        },
        onStepComplete: (step, res) => {
          console.log(`[ClosedLoop] Step complete: ${step.id} -> ${res.success ? 'SUCCESS' : 'FAILED'} (Stage: ${res.stageUsed})`);
        },
        onPlanComplete: (_success, _summary) => {
          setIsExecuting(false);
        },
        onRescanRequested: async () => {
          sendCommand({ type: 'SCAN_OCR', timestamp: Date.now() });
          return boundingBoxes;
        },
        onAuditLog: addAuditLog
      }
    );

    voiceListenerRef.current = new WakeWordListener({
      wakePhrase: 'hey zenith',
      onWakeWordDetected: (utterance) => {
        setVoiceStatusText(`Heard: "${utterance}"`);
        addAuditLog('LOCAL_NPU_QNN', `WAKE_TRIGGER: "${utterance}"`, undefined, 0.98, 200, 16, 'SUCCESS');
      },
      onIntentParsed: async (steps, summary) => {
        setVoiceStatusText(`Executing: ${summary}`);
        if (executorRef.current) {
          setIsExecuting(true);
          await executorRef.current.executePlan(steps, boundingBoxes);
        }
      },
      onStatusChange: (listening, msg) => {
        setIsVoiceListening(listening);
        setVoiceStatusText(msg);
      }
    });
  }, [hostInput, portInput, officeKitBridge, sendCommand, boundingBoxes, addAuditLog]);

  // Load Saved Macros
  useEffect(() => {
    try {
      const stored = localStorage.getItem('zenith_saved_macros');
      if (stored) {
        setSavedMacros(JSON.parse(stored));
      }
    } catch (_) {}
  }, []);

  const saveMacrosToStorage = (macros: MacroRecipe[]) => {
    setSavedMacros(macros);
    try {
      localStorage.setItem('zenith_saved_macros', JSON.stringify(macros));
    } catch (_) {}
  };

  // Autonomous Prompt Execution
  const handleExecutePrompt = async (prompt: string) => {
    if (!executorRef.current) return;
    setIsExecuting(true);

    const grounding = await npuEngineRef.current.parseGrounding(prompt, boundingBoxes, latestBitmap);
    const target = grounding.bestTarget;

    const steps: ActionStep[] = [
      {
        id: `step_${Date.now()}`,
        action: 'CLICK',
        target: target ? {
          x: Math.round(target.x + target.width / 2),
          y: Math.round(target.y + target.height / 2),
          label: target.label,
          confidence: target.confidence
        } : {
          x: 500,
          y: 500,
          label: prompt,
          confidence: 0.85
        },
        payload: prompt,
        description: `Execute "${prompt}"`,
        timeoutMs: 4000,
        expectedMutation: true,
        confidence: target?.confidence || 0.85,
        stage: 'PRIMARY'
      }
    ];

    await executorRef.current.executePlan(steps, boundingBoxes);
  };

  const handleStartMacroRecord = () => {
    macroRecorderRef.current.start();
    setIsRecordingMacro(true);
    addAuditLog('LOCAL_NPU_QNN', 'MACRO_RECORD_START', undefined, 1.0, 200, 0, 'SUCCESS');
  };

  const handleStopMacroRecord = () => {
    const recipe = macroRecorderRef.current.stop();
    setIsRecordingMacro(false);
    if (recipe.steps.length > 0) {
      const updated = [...savedMacros, recipe];
      saveMacrosToStorage(updated);
      addAuditLog('LOCAL_NPU_QNN', 'MACRO_SAVED', `Steps: ${recipe.steps.length}`, 1.0, 200, 0, 'SUCCESS');
    }
  };

  const handlePlayMacro = async (macro: MacroRecipe) => {
    if (!executorRef.current || isExecuting) return;
    setIsExecuting(true);
    addAuditLog('LOCAL_NPU_QNN', `MACRO_PLAY: ${macro.name}`, undefined, 0.98, 200, 0, 'SUCCESS');
    await executorRef.current.executePlan(macro.steps, boundingBoxes);
  };

  const handleDeleteMacro = (id: string) => {
    const updated = savedMacros.filter(m => m.id !== id);
    saveMacrosToStorage(updated);
  };

  const handleScanOcr = () => {
    sendCommand({ type: 'SCAN_OCR', timestamp: Date.now() });
    addAuditLog('LOCAL_NPU_QNN', 'SCAN_OCR', undefined, 0.98, 200, 16, 'SUCCESS');
  };

  const handleToggleVoice = () => {
    if (isVoiceListening) {
      voiceListenerRef.current?.stopListening();
    } else {
      voiceListenerRef.current?.startListening();
    }
  };

  const handleApplyEndpoint = (e: React.FormEvent) => {
    e.preventDefault();
    setBridgeEndpoint(hostInput, portInput);
    executorRef.current?.updateEndpoint(hostInput, portInput);
    setIsSettingsOpen(false);
    connect();
  };

  return (
    <>
      <Head>
        <title>Zenith Spatial Co-Pilot | Snapdragon NPU & iQOO Office Kit HUD</title>
        <meta name="description" content="Ultra-low latency spatial UI automation dashboard for Android over iQOO Office Kit & local bridge" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <link rel="icon" href="/favicon.ico" />
      </Head>

      <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', width: '100vw', backgroundColor: '#06080F', color: '#FFFFFF' }}>
        {/* Hardware Cyberpunk HUD Header */}
        <header
          style={{
            height: '60px',
            backgroundColor: 'rgba(11, 15, 25, 0.92)',
            backdropFilter: 'blur(16px)',
            borderBottom: '1px solid rgba(0, 240, 255, 0.2)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            padding: '0 24px',
            flexShrink: 0
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <div
              style={{
                background: 'linear-gradient(135deg, #00F0FF, #00FF66)',
                color: '#000000',
                fontWeight: 900,
                fontSize: '12px',
                padding: '4px 8px',
                borderRadius: '4px',
                letterSpacing: '1px',
                fontFamily: 'monospace'
              }}
            >
              ZENITH AI
            </div>
            <div style={{ fontSize: '16px', fontWeight: 900, letterSpacing: '0.8px', fontFamily: 'monospace' }}>
              SPATIAL CO-PILOT <span style={{ color: '#00F0FF', fontSize: '11px' }}>// iQOO OFFICE KIT + QNN</span>
            </div>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                backgroundColor: '#090D18',
                padding: '6px 14px',
                borderRadius: '20px',
                border: '1px solid rgba(0, 240, 255, 0.25)',
                fontSize: '11px',
                fontFamily: 'monospace'
              }}
            >
              <span style={{ color: '#8A99AD' }}>BRIDGE:</span>
              <span style={{ color: '#00F0FF', fontWeight: 700 }}>{hostInput}:{portInput}</span>
            </div>

            <button
              onClick={() => setIsSettingsOpen(!isSettingsOpen)}
              style={{
                backgroundColor: '#0E131F',
                border: '1px solid rgba(0, 240, 255, 0.3)',
                color: '#FFFFFF',
                borderRadius: '8px',
                padding: '6px 12px',
                fontSize: '11px',
                fontWeight: 800,
                cursor: 'pointer',
                fontFamily: 'monospace'
              }}
            >
              ⚙ CONFIG
            </button>
          </div>
        </header>

        {/* Configuration Drawer */}
        {isSettingsOpen && (
          <div
            style={{
              backgroundColor: 'rgba(14, 19, 31, 0.95)',
              borderBottom: '1px solid rgba(0, 240, 255, 0.25)',
              padding: '14px 24px',
              display: 'flex',
              alignItems: 'center',
              gap: '16px'
            }}
          >
            <form onSubmit={handleApplyEndpoint} style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
              <span style={{ fontSize: '12px', fontWeight: 800, color: '#00F0FF', fontFamily: 'monospace' }}>TARGET HOST:</span>
              <input
                type="text"
                value={hostInput}
                onChange={e => setHostInput(e.target.value)}
                placeholder="192.168.1.3"
                style={{
                  backgroundColor: '#06080F',
                  border: '1px solid rgba(0, 240, 255, 0.25)',
                  borderRadius: '6px',
                  padding: '6px 12px',
                  color: '#FFFFFF',
                  fontSize: '11px',
                  fontFamily: 'monospace'
                }}
              />
              <span style={{ fontSize: '12px', fontWeight: 800, color: '#00F0FF', fontFamily: 'monospace' }}>PORT:</span>
              <input
                type="number"
                value={portInput}
                onChange={e => setPortInput(parseInt(e.target.value) || 8080)}
                style={{
                  backgroundColor: '#06080F',
                  border: '1px solid rgba(0, 240, 255, 0.25)',
                  borderRadius: '6px',
                  padding: '6px 12px',
                  color: '#FFFFFF',
                  fontSize: '11px',
                  width: '90px',
                  fontFamily: 'monospace'
                }}
              />
              <button
                type="submit"
                style={{
                  backgroundColor: '#00F0FF',
                  color: '#000000',
                  fontWeight: 900,
                  fontSize: '11px',
                  border: 'none',
                  borderRadius: '6px',
                  padding: '6px 16px',
                  cursor: 'pointer',
                  fontFamily: 'monospace'
                }}
              >
                APPLY & CONNECT
              </button>
            </form>
          </div>
        )}

        {/* Main Tri-Panel Grid */}
        <main
          style={{
            flex: 1,
            display: 'grid',
            gridTemplateColumns: 'minmax(330px, 440px) 1fr minmax(330px, 420px)',
            gap: '16px',
            padding: '16px',
            overflow: 'hidden'
          }}
        >
          {/* Left Panel: Autonomous Control, Hero Routines, Voice & Macro */}
          <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
            <ControlPanel
              onExecutePrompt={handleExecutePrompt}
              onNavAction={sendNavAction}
              onTextInput={sendTextInput}
              onScanOcr={handleScanOcr}
              isExecuting={isExecuting}
              isRecordingMacro={isRecordingMacro}
              onStartMacroRecord={handleStartMacroRecord}
              onStopMacroRecord={handleStopMacroRecord}
              savedMacros={savedMacros}
              onPlayMacro={handlePlayMacro}
              onDeleteMacro={handleDeleteMacro}
              isVoiceListening={isVoiceListening}
              onToggleVoice={handleToggleVoice}
              voiceStatusText={voiceStatusText}
            />
          </div>

          {/* Center Panel: Primary Hardware Screen Canvas */}
          <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
            <ScreenCanvas
              latestBitmap={latestBitmap}
              boundingBoxes={boundingBoxes}
              showBoundingBoxes={showBoundingBoxes}
              showTargetOffsets={showTargetOffsets}
              enablePrivacyMasking={enablePrivacyMasking}
              onRemoteTap={(x, y) => sendRemoteTouch(x, y, 'CLICK')}
              onRemoteSwipe={sendRemoteSwipe}
              onRemoteLongPress={(x, y) => sendRemoteTouch(x, y, 'LONG_PRESS')}
              onMacroPointerDown={(x, y, label) => macroRecorderRef.current.recordPointerDown(x, y, label)}
              onMacroPointerUp={(x, y, start) => macroRecorderRef.current.recordPointerUp(x, y, start)}
              isMacroRecording={isRecordingMacro}
            />
          </div>

          {/* Right Panel: Hardware HUD Telemetry & Action Audit Stream */}
          <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
            <TelemetryPanel
              bridgeStatus={bridgeStatus}
              bridgeProtocol={bridgeProtocol}
              fps={fps}
              frameLatencyMs={frameLatencyMs}
              npuInferenceTimeMs={npuInferenceTimeMs}
              npuUsagePercent={npuUsagePercent}
              confidenceMatrix={confidenceMatrix}
              executionProvider={executionProvider}
              heapMemoryMb={heapMemoryMb}
              activeApp={activeApp}
              auditLogs={auditLogs}
              showBoundingBoxes={showBoundingBoxes}
              setShowBoundingBoxes={setShowBoundingBoxes}
              showTargetOffsets={showTargetOffsets}
              setShowTargetOffsets={setShowTargetOffsets}
              enablePrivacyMasking={enablePrivacyMasking}
              setEnablePrivacyMasking={setEnablePrivacyMasking}
              onClearLogs={() => setAuditLogs([])}
            />
          </div>
        </main>
      </div>
    </>
  );
}
