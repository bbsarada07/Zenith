import React, { useState } from 'react';
import { MacroRecipe } from '@/lib/macroRecorder';
import { HERO_ROUTINES } from '@/lib/macros/heroRoutines';

export interface ControlPanelProps {
  onExecutePrompt: (prompt: string) => Promise<void>;
  onNavAction: (action: 'HOME' | 'BACK' | 'RECENTS' | 'NOTIFICATIONS') => void;
  onTextInput: (text: string) => void;
  onScanOcr: () => void;
  isExecuting: boolean;
  isRecordingMacro: boolean;
  onStartMacroRecord: () => void;
  onStopMacroRecord: () => void;
  savedMacros: MacroRecipe[];
  onPlayMacro: (macro: MacroRecipe) => void;
  onDeleteMacro?: (id: string) => void;
  isVoiceListening: boolean;
  onToggleVoice: () => void;
  voiceStatusText: string;
}

export const ControlPanel: React.FC<ControlPanelProps> = ({
  onExecutePrompt,
  onNavAction,
  onTextInput,
  onScanOcr,
  isExecuting,
  isRecordingMacro,
  onStartMacroRecord,
  onStopMacroRecord,
  savedMacros,
  onPlayMacro,
  onDeleteMacro,
  isVoiceListening,
  onToggleVoice,
  voiceStatusText
}) => {
  const [prompt, setPrompt] = useState<string>('');
  const [inputText, setInputText] = useState<string>('');

  const handlePromptSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!prompt.trim() || isExecuting) return;
    const task = prompt.trim();
    setPrompt('');
    await onExecutePrompt(task);
  };

  const handleTextSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!inputText.trim()) return;
    onTextInput(inputText.trim());
    setInputText('');
  };

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: '14px',
        backgroundColor: 'rgba(14, 19, 31, 0.85)',
        backdropFilter: 'blur(16px)',
        borderRadius: '16px',
        border: '1px solid rgba(0, 240, 255, 0.2)',
        padding: '20px',
        color: '#FFFFFF',
        boxShadow: '0 12px 36px rgba(0, 0, 0, 0.6), inset 0 0 20px rgba(0, 240, 255, 0.03)',
        overflowY: 'auto'
      }}
    >
      {/* Voice Wake-Word Agent Status & Toggle */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          backgroundColor: '#070A12',
          padding: '10px 14px',
          borderRadius: '10px',
          border: '1px solid rgba(0, 240, 255, 0.2)'
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <div
            style={{
              width: '8px',
              height: '8px',
              borderRadius: '50%',
              backgroundColor: isVoiceListening ? '#00FF66' : '#8A99AD',
              boxShadow: isVoiceListening ? '0 0 10px #00FF66' : 'none'
            }}
          />
          <div>
            <div style={{ fontSize: '11px', fontWeight: 800, color: '#00F0FF', fontFamily: 'monospace' }}>
              VOICE WAKE-WORD AGENT
            </div>
            <div style={{ fontSize: '10px', color: '#8A99AD', fontFamily: 'monospace' }}>
              {voiceStatusText}
            </div>
          </div>
        </div>

        <button
          onClick={onToggleVoice}
          style={{
            backgroundColor: isVoiceListening ? 'rgba(255, 46, 84, 0.2)' : 'rgba(0, 255, 102, 0.15)',
            border: `1px solid ${isVoiceListening ? '#FF2E54' : '#00FF66'}`,
            color: isVoiceListening ? '#FF2E54' : '#00FF66',
            borderRadius: '8px',
            padding: '4px 12px',
            fontSize: '11px',
            fontWeight: 800,
            cursor: 'pointer',
            fontFamily: 'monospace'
          }}
        >
          {isVoiceListening ? 'MUTE MIC' : 'ACTIVATE ("Hey Zenith")'}
        </button>
      </div>

      {/* Autonomous Intent Execution Bar */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontSize: '11px', fontWeight: 900, color: '#00F0FF', letterSpacing: '0.6px', fontFamily: 'monospace' }}>
            AUTONOMOUS INTENT RUNTIME
          </span>
          {isExecuting && (
            <span style={{ fontSize: '10px', color: '#FFB800', fontWeight: 800, fontFamily: 'monospace' }}>
              ⚡ EXECUTING TRI-STAGE PLAN...
            </span>
          )}
        </div>

        <form onSubmit={handlePromptSubmit} style={{ display: 'flex', gap: '8px' }}>
          <input
            type="text"
            value={prompt}
            onChange={e => setPrompt(e.target.value)}
            placeholder='e.g., "Open WhatsApp and send message", "Toggle Dark Mode"'
            disabled={isExecuting}
            style={{
              flex: 1,
              backgroundColor: '#070A12',
              border: '1px solid rgba(0, 240, 255, 0.25)',
              borderRadius: '8px',
              padding: '10px 14px',
              color: '#FFFFFF',
              fontSize: '12px',
              outline: 'none',
              fontFamily: 'monospace'
            }}
          />
          <button
            type="submit"
            disabled={isExecuting || !prompt.trim()}
            style={{
              backgroundColor: isExecuting ? '#1A2333' : '#00F0FF',
              color: '#000000',
              fontWeight: 900,
              fontSize: '12px',
              border: 'none',
              borderRadius: '8px',
              padding: '0 16px',
              cursor: isExecuting ? 'not-allowed' : 'pointer',
              fontFamily: 'monospace',
              boxShadow: isExecuting ? 'none' : '0 0 14px rgba(0, 240, 255, 0.4)'
            }}
          >
            DISPATCH
          </button>
        </form>
      </div>

      {/* Hero Use Case Demo Routines (Under 45s deterministic execution) */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
        <span style={{ fontSize: '10px', color: '#8A99AD', fontWeight: 800, letterSpacing: '0.6px', fontFamily: 'monospace' }}>
          HERO USE CASE DEMO ROUTINES (&lt; 45s)
        </span>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '6px' }}>
          <button
            onClick={() => onPlayMacro(HERO_ROUTINES.ROUTINE_A_PRODUCTIVITY)}
            disabled={isExecuting}
            style={{
              backgroundColor: '#090D18',
              border: '1px solid rgba(0, 240, 255, 0.2)',
              borderRadius: '8px',
              padding: '8px',
              color: '#FFFFFF',
              fontSize: '10px',
              fontWeight: 700,
              fontFamily: 'monospace',
              cursor: isExecuting ? 'not-allowed' : 'pointer',
              textAlign: 'left'
            }}
          >
            <div style={{ color: '#00F0FF', fontWeight: 800 }}>ROUTINE A</div>
            <div style={{ color: '#8A99AD', fontSize: '9px' }}>WhatsApp $\to$ Notes</div>
          </button>

          <button
            onClick={() => onPlayMacro(HERO_ROUTINES.ROUTINE_B_SYSTEM_OPS)}
            disabled={isExecuting}
            style={{
              backgroundColor: '#090D18',
              border: '1px solid rgba(0, 255, 102, 0.2)',
              borderRadius: '8px',
              padding: '8px',
              color: '#FFFFFF',
              fontSize: '10px',
              fontWeight: 700,
              fontFamily: 'monospace',
              cursor: isExecuting ? 'not-allowed' : 'pointer',
              textAlign: 'left'
            }}
          >
            <div style={{ color: '#00FF66', fontWeight: 800 }}>ROUTINE B</div>
            <div style={{ color: '#8A99AD', fontSize: '9px' }}>Settings $\to$ Battery</div>
          </button>

          <button
            onClick={() => onPlayMacro(HERO_ROUTINES.ROUTINE_C_CROSS_APP_CALL)}
            disabled={isExecuting}
            style={{
              backgroundColor: '#090D18',
              border: '1px solid rgba(255, 184, 0, 0.2)',
              borderRadius: '8px',
              padding: '8px',
              color: '#FFFFFF',
              fontSize: '10px',
              fontWeight: 700,
              fontFamily: 'monospace',
              cursor: isExecuting ? 'not-allowed' : 'pointer',
              textAlign: 'left'
            }}
          >
            <div style={{ color: '#FFB800', fontWeight: 800 }}>ROUTINE C</div>
            <div style={{ color: '#8A99AD', fontSize: '9px' }}>Directory $\to$ Call</div>
          </button>
        </div>
      </div>

      {/* Core Device Navigation Keys */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
        <span style={{ fontSize: '10px', color: '#8A99AD', fontWeight: 800, letterSpacing: '0.6px', fontFamily: 'monospace' }}>
          CORE NAVIGATION & HARDWARE CONTROLS
        </span>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '6px' }}>
          <button
            onClick={() => onNavAction('BACK')}
            style={{
              backgroundColor: '#070A12',
              border: '1px solid rgba(0, 240, 255, 0.15)',
              color: '#FFFFFF',
              borderRadius: '8px',
              padding: '8px',
              fontSize: '11px',
              fontWeight: 800,
              fontFamily: 'monospace',
              cursor: 'pointer'
            }}
          >
            ◀ BACK
          </button>

          <button
            onClick={() => onNavAction('HOME')}
            style={{
              backgroundColor: '#070A12',
              border: '1px solid rgba(0, 240, 255, 0.3)',
              color: '#00F0FF',
              borderRadius: '8px',
              padding: '8px',
              fontSize: '11px',
              fontWeight: 800,
              fontFamily: 'monospace',
              cursor: 'pointer'
            }}
          >
            ● HOME
          </button>

          <button
            onClick={() => onNavAction('RECENTS')}
            style={{
              backgroundColor: '#070A12',
              border: '1px solid rgba(0, 240, 255, 0.15)',
              color: '#FFFFFF',
              borderRadius: '8px',
              padding: '8px',
              fontSize: '11px',
              fontWeight: 800,
              fontFamily: 'monospace',
              cursor: 'pointer'
            }}
          >
            ■ RECENTS
          </button>

          <button
            onClick={onScanOcr}
            style={{
              backgroundColor: 'rgba(0, 240, 255, 0.1)',
              border: '1px solid #00F0FF',
              color: '#00F0FF',
              borderRadius: '8px',
              padding: '8px',
              fontSize: '11px',
              fontWeight: 800,
              fontFamily: 'monospace',
              cursor: 'pointer'
            }}
          >
            🔍 SCAN OCR
          </button>
        </div>
      </div>

      {/* Direct Remote Keyboard Injection */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
        <span style={{ fontSize: '10px', color: '#8A99AD', fontWeight: 800, letterSpacing: '0.6px', fontFamily: 'monospace' }}>
          REMOTE KEYBOARD INJECTION
        </span>
        <form onSubmit={handleTextSubmit} style={{ display: 'flex', gap: '6px' }}>
          <input
            type="text"
            value={inputText}
            onChange={e => setInputText(e.target.value)}
            placeholder="Inject text into active field..."
            style={{
              flex: 1,
              backgroundColor: '#070A12',
              border: '1px solid rgba(0, 240, 255, 0.2)',
              borderRadius: '8px',
              padding: '8px 12px',
              color: '#FFFFFF',
              fontSize: '11px',
              fontFamily: 'monospace',
              outline: 'none'
            }}
          />
          <button
            type="submit"
            disabled={!inputText.trim()}
            style={{
              backgroundColor: '#090D18',
              border: '1px solid #00F0FF',
              color: '#00F0FF',
              fontWeight: 800,
              fontSize: '11px',
              fontFamily: 'monospace',
              borderRadius: '8px',
              padding: '0 14px',
              cursor: 'pointer'
            }}
          >
            SEND
          </button>
        </form>
      </div>

      {/* Macro Automation Recorder */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontSize: '10px', color: '#8A99AD', fontWeight: 800, letterSpacing: '0.6px', fontFamily: 'monospace' }}>
            SPATIAL MACRO RECORDER
          </span>
          <button
            onClick={isRecordingMacro ? onStopMacroRecord : onStartMacroRecord}
            style={{
              backgroundColor: isRecordingMacro ? '#FF2E54' : 'rgba(0, 255, 102, 0.15)',
              border: `1px solid ${isRecordingMacro ? '#FF2E54' : '#00FF66'}`,
              color: isRecordingMacro ? '#FFFFFF' : '#00FF66',
              borderRadius: '6px',
              padding: '3px 10px',
              fontSize: '10px',
              fontWeight: 800,
              fontFamily: 'monospace',
              cursor: 'pointer'
            }}
          >
            {isRecordingMacro ? '⏹ STOP RECORDING' : '● RECORD MACRO'}
          </button>
        </div>

        {/* Saved Macros */}
        {savedMacros.length > 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', maxHeight: '110px', overflowY: 'auto' }}>
            {savedMacros.map(macro => (
              <div
                key={macro.id}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  backgroundColor: '#070A12',
                  padding: '6px 10px',
                  borderRadius: '6px',
                  border: '1px solid rgba(0, 240, 255, 0.15)'
                }}
              >
                <div>
                  <div style={{ fontSize: '11px', fontWeight: 800, color: '#FFFFFF', fontFamily: 'monospace' }}>{macro.name}</div>
                  <div style={{ fontSize: '9px', color: '#8A99AD', fontFamily: 'monospace' }}>{macro.steps.length} steps · {Math.round(macro.totalDurationMs / 1000)}s</div>
                </div>
                <div style={{ display: 'flex', gap: '4px' }}>
                  <button
                    onClick={() => onPlayMacro(macro)}
                    disabled={isExecuting}
                    style={{
                      backgroundColor: '#00FF66',
                      color: '#000000',
                      border: 'none',
                      borderRadius: '4px',
                      padding: '3px 8px',
                      fontSize: '10px',
                      fontWeight: 900,
                      fontFamily: 'monospace',
                      cursor: isExecuting ? 'not-allowed' : 'pointer'
                    }}
                  >
                    ▶ PLAY
                  </button>
                  {onDeleteMacro && (
                    <button
                      onClick={() => onDeleteMacro(macro.id)}
                      style={{
                        backgroundColor: 'rgba(255, 46, 84, 0.15)',
                        color: '#FF2E54',
                        border: '1px solid rgba(255, 46, 84, 0.4)',
                        borderRadius: '4px',
                        padding: '3px 6px',
                        fontSize: '10px',
                        fontFamily: 'monospace',
                        cursor: 'pointer'
                      }}
                    >
                      ✕
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};
