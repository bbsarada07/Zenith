import React, { useState, useRef, useCallback, useEffect } from 'react';
import Head from 'next/head';
import { useBridgeStream } from '@/hooks/useBridgeStream';
import { ScreenCanvas } from '@/components/ScreenCanvas';
import { TelemetryPanel } from '@/components/TelemetryPanel';
import { ControlPanel } from '@/components/ControlPanel';
import { TaskExecutionEngine } from '@/lib/taskExecutionEngine';
import { MacroRecorder, MacroRecipe } from '@/lib/macroRecorder';
import { ActionAuditLog } from '@/lib/schemas/actionSchema';

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

  const auditLogIdCounter = useRef<number>(0);

  const addAuditLog = useCallback((
    command: string,
    targetBBox: string | undefined,
    statusCode: number,
    latencyMs: number,
    status: 'SUCCESS' | 'RETRY' | 'FAILED'
  ) => {
    const newLog: ActionAuditLog = {
      id: `log_${++auditLogIdCounter.current}`,
      timestamp: new Date().toISOString().slice(11, 23),
      command,
      targetBBox,
      statusCode,
      latencyMs,
      status
    };

    setAuditLogs(prev => [...prev.slice(-49), newLog]);
  }, []);

  // Bridge Stream Hook
  const {
    bridgeStatus,
    fps,
    frameLatencyMs,
    npuUsagePercent,
    heapMemoryMb,
    activeApp,
    boundingBoxes,
    latestBitmap,
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

  // Task Execution Engine Ref
  const taskEngineRef = useRef<TaskExecutionEngine | null>(null);

  // Macro Recorder Ref
  const macroRecorderRef = useRef<MacroRecorder>(
    new MacroRecorder((isRec) => {
      setIsRecordingMacro(isRec);
    })
  );

  // Initialize Task Engine
  useEffect(() => {
    taskEngineRef.current = new TaskExecutionEngine(
      hostInput,
      portInput,
      sendCommand,
      {
        onStepStart: (step, idx, total) => {
          console.log(`[TaskEngine] Step ${idx}/${total}: ${step.description}`);
        },
        onStepComplete: (step, res) => {
          console.log(`[TaskEngine] Step complete: ${step.id} -> ${res.success ? 'SUCCESS' : 'FAILED'}`);
        },
        onPlanComplete: (success, summary) => {
          setIsExecuting(false);
          console.log(`[TaskEngine] Plan finished: ${summary}`);
        },
        onRescanRequested: async () => {
          sendCommand({ type: 'SCAN_OCR', timestamp: Date.now() });
          return boundingBoxes;
        },
        onAuditLog: addAuditLog
      }
    );
  }, [hostInput, portInput, sendCommand, boundingBoxes, addAuditLog]);

  // Load Saved Macros from LocalStorage
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
    if (!taskEngineRef.current) return;
    setIsExecuting(true);
    const steps = taskEngineRef.current.parsePromptToPlan(prompt, boundingBoxes);
    await taskEngineRef.current.executePlan(steps);
  };

  // Macro Recording Lifecycle
  const handleStartMacroRecord = () => {
    macroRecorderRef.current.start();
    setIsRecordingMacro(true);
    addAuditLog('MACRO_RECORD_START', undefined, 200, 0, 'SUCCESS');
  };

  const handleStopMacroRecord = () => {
    const recipe = macroRecorderRef.current.stop();
    setIsRecordingMacro(false);
    if (recipe.steps.length > 0) {
      const updated = [...savedMacros, recipe];
      saveMacrosToStorage(updated);
      addAuditLog('MACRO_RECORD_SAVED', `Steps: ${recipe.steps.length}`, 200, 0, 'SUCCESS');
    }
  };

  const handlePlayMacro = async (macro: MacroRecipe) => {
    if (!taskEngineRef.current || isExecuting) return;
    setIsExecuting(true);
    addAuditLog('MACRO_PLAY_START', macro.name, 200, 0, 'SUCCESS');
    await taskEngineRef.current.executePlan(macro.steps);
  };

  const handleDeleteMacro = (id: string) => {
    const updated = savedMacros.filter(m => m.id !== id);
    saveMacrosToStorage(updated);
  };

  const handleScanOcr = () => {
    sendCommand({ type: 'SCAN_OCR', timestamp: Date.now() });
    addAuditLog('SCAN_OCR', undefined, 200, 15, 'SUCCESS');
  };

  const handleApplyEndpoint = (e: React.FormEvent) => {
    e.preventDefault();
    setBridgeEndpoint(hostInput, portInput);
    taskEngineRef.current?.setEndpoint(hostInput, portInput);
    setIsSettingsOpen(false);
    connect();
  };

  return (
    <>
      <Head>
        <title>Zenith Spatial Co-Pilot | Autonomous Android Engine</title>
        <meta name="description" content="Ultra-low latency spatial UI automation dashboard for Android over local bridge" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <link rel="icon" href="/favicon.ico" />
      </Head>

      <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', width: '100vw', backgroundColor: '#07090e', color: '#f0f4f8' }}>
        {/* Top Cyberpunk Header */}
        <header
          style={{
            height: '64px',
            backgroundColor: '#0c0e14',
            borderBottom: '1px solid #1f2737',
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
                background: 'linear-gradient(135deg, #ffe600, #ff9900)',
                color: '#000',
                fontWeight: 900,
                fontSize: '13px',
                padding: '4px 8px',
                borderRadius: '4px',
                letterSpacing: '1px'
              }}
            >
              ZENITH
            </div>
            <div style={{ fontSize: '18px', fontWeight: 800, letterSpacing: '0.5px' }}>
              SPATIAL CO-PILOT <span style={{ color: '#00f0ff', fontSize: '12px' }}>// ZENO ENGINE</span>
            </div>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                backgroundColor: '#121620',
                padding: '6px 14px',
                borderRadius: '20px',
                border: '1px solid #1c2331',
                fontSize: '12px',
                fontFamily: 'monospace'
              }}
            >
              <span style={{ color: '#8a99ad' }}>BRIDGE:</span>
              <span style={{ color: '#00f0ff' }}>{hostInput}:{portInput}</span>
            </div>

            <button
              onClick={() => setIsSettingsOpen(!isSettingsOpen)}
              style={{
                backgroundColor: '#182232',
                border: '1px solid #232d3f',
                color: '#f0f4f8',
                borderRadius: '8px',
                padding: '6px 12px',
                fontSize: '12px',
                fontWeight: 600,
                cursor: 'pointer'
              }}
            >
              ⚙ CONFIG
            </button>
          </div>
        </header>

        {/* Bridge Endpoint Config Drawer */}
        {isSettingsOpen && (
          <div
            style={{
              backgroundColor: '#121620',
              borderBottom: '1px solid #1f2737',
              padding: '16px 24px',
              display: 'flex',
              alignItems: 'center',
              gap: '16px'
            }}
          >
            <form onSubmit={handleApplyEndpoint} style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
              <span style={{ fontSize: '13px', fontWeight: 700, color: '#00f0ff' }}>TARGET HOST:</span>
              <input
                type="text"
                value={hostInput}
                onChange={e => setHostInput(e.target.value)}
                placeholder="192.168.1.3"
                style={{
                  backgroundColor: '#07090e',
                  border: '1px solid #232d3f',
                  borderRadius: '6px',
                  padding: '6px 12px',
                  color: '#f0f4f8',
                  fontSize: '12px',
                  fontFamily: 'monospace'
                }}
              />
              <span style={{ fontSize: '13px', fontWeight: 700, color: '#00f0ff' }}>PORT:</span>
              <input
                type="number"
                value={portInput}
                onChange={e => setPortInput(parseInt(e.target.value) || 8080)}
                style={{
                  backgroundColor: '#07090e',
                  border: '1px solid #232d3f',
                  borderRadius: '6px',
                  padding: '6px 12px',
                  color: '#f0f4f8',
                  fontSize: '12px',
                  width: '90px',
                  fontFamily: 'monospace'
                }}
              />
              <button
                type="submit"
                style={{
                  backgroundColor: '#00f0ff',
                  color: '#000',
                  fontWeight: 700,
                  fontSize: '12px',
                  border: 'none',
                  borderRadius: '6px',
                  padding: '6px 16px',
                  cursor: 'pointer'
                }}
              >
                CONNECT
              </button>
            </form>
          </div>
        )}

        {/* Main Dashboard Workspace Grid */}
        <main
          style={{
            flex: 1,
            display: 'grid',
            gridTemplateColumns: 'minmax(320px, 460px) 1fr minmax(320px, 420px)',
            gap: '20px',
            padding: '20px',
            overflow: 'hidden'
          }}
        >
          {/* Left Column: Autonomous Intent & Navigation Controls */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '20px', overflowY: 'auto' }}>
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
            />
          </div>

          {/* Center Column: Live HTML5 Screen Canvas */}
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

          {/* Right Column: Engine Telemetry & Action Audit Stream */}
          <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
            <TelemetryPanel
              bridgeStatus={bridgeStatus}
              fps={fps}
              frameLatencyMs={frameLatencyMs}
              npuUsagePercent={npuUsagePercent}
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
