import React, { useState } from 'react';
import { MacroRecipe } from '@/lib/macroRecorder';

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
  onDeleteMacro
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
        gap: '16px',
        backgroundColor: '#0c0e14',
        borderRadius: '16px',
        border: '1px solid #1f2737',
        padding: '20px',
        color: '#f0f4f8',
        boxShadow: '0 10px 30px rgba(0,0,0,0.5)'
      }}
    >
      {/* Autonomous Intent Execution Form */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontSize: '13px', fontWeight: 800, color: '#00f0ff', letterSpacing: '0.6px' }}>
            AUTONOMOUS CO-PILOT INTENT
          </span>
          {isExecuting && (
            <span style={{ fontSize: '11px', color: '#ffe600', fontWeight: 700, animation: 'pulse 1.5s infinite' }}>
              ⚡ EXECUTING PLAN...
            </span>
          )}
        </div>

        <form onSubmit={handlePromptSubmit} style={{ display: 'flex', gap: '8px' }}>
          <input
            type="text"
            value={prompt}
            onChange={e => setPrompt(e.target.value)}
            placeholder='e.g., "Search for Nike Shoes", "Open Settings", "Scroll Down"'
            disabled={isExecuting}
            style={{
              flex: 1,
              backgroundColor: '#121620',
              border: '1px solid #232d3f',
              borderRadius: '10px',
              padding: '12px 16px',
              color: '#f0f4f8',
              fontSize: '13px',
              outline: 'none'
            }}
          />
          <button
            type="submit"
            disabled={isExecuting || !prompt.trim()}
            style={{
              backgroundColor: isExecuting ? '#334155' : '#00f0ff',
              color: '#000',
              fontWeight: 800,
              fontSize: '13px',
              border: 'none',
              borderRadius: '10px',
              padding: '0 20px',
              cursor: isExecuting ? 'not-allowed' : 'pointer',
              boxShadow: isExecuting ? 'none' : '0 0 15px rgba(0,240,255,0.4)',
              transition: 'all 0.2s ease'
            }}
          >
            DISPATCH
          </button>
        </form>
      </div>

      {/* Core Device Navigation Bar */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <span style={{ fontSize: '11px', color: '#8a99ad', fontWeight: 700, letterSpacing: '0.5px' }}>
          CORE NAVIGATION & HARDWARE CONTROLS
        </span>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '8px' }}>
          <button
            onClick={() => onNavAction('BACK')}
            style={{
              backgroundColor: '#121620',
              border: '1px solid #1c2331',
              color: '#f0f4f8',
              borderRadius: '10px',
              padding: '10px',
              fontSize: '12px',
              fontWeight: 700,
              cursor: 'pointer'
            }}
          >
            ◀ BACK
          </button>

          <button
            onClick={() => onNavAction('HOME')}
            style={{
              backgroundColor: '#121620',
              border: '1px solid #1c2331',
              color: '#00f0ff',
              borderRadius: '10px',
              padding: '10px',
              fontSize: '12px',
              fontWeight: 700,
              cursor: 'pointer'
            }}
          >
            ● HOME
          </button>

          <button
            onClick={() => onNavAction('RECENTS')}
            style={{
              backgroundColor: '#121620',
              border: '1px solid #1c2331',
              color: '#f0f4f8',
              borderRadius: '10px',
              padding: '10px',
              fontSize: '12px',
              fontWeight: 700,
              cursor: 'pointer'
            }}
          >
            ■ RECENTS
          </button>

          <button
            onClick={onScanOcr}
            style={{
              backgroundColor: '#172230',
              border: '1px solid #00f0ff44',
              color: '#00f0ff',
              borderRadius: '10px',
              padding: '10px',
              fontSize: '12px',
              fontWeight: 700,
              cursor: 'pointer'
            }}
          >
            🔍 SCAN OCR
          </button>
        </div>
      </div>

      {/* Direct Remote Text Injection */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <span style={{ fontSize: '11px', color: '#8a99ad', fontWeight: 700, letterSpacing: '0.5px' }}>
          REMOTE KEYBOARD INJECTION
        </span>
        <form onSubmit={handleTextSubmit} style={{ display: 'flex', gap: '8px' }}>
          <input
            type="text"
            value={inputText}
            onChange={e => setInputText(e.target.value)}
            placeholder="Type text to inject into active focused field..."
            style={{
              flex: 1,
              backgroundColor: '#121620',
              border: '1px solid #232d3f',
              borderRadius: '10px',
              padding: '10px 14px',
              color: '#f0f4f8',
              fontSize: '12px',
              outline: 'none'
            }}
          />
          <button
            type="submit"
            disabled={!inputText.trim()}
            style={{
              backgroundColor: '#182436',
              border: '1px solid #00f0ff66',
              color: '#00f0ff',
              fontWeight: 700,
              fontSize: '12px',
              borderRadius: '10px',
              padding: '0 16px',
              cursor: 'pointer'
            }}
          >
            SEND
          </button>
        </form>
      </div>

      {/* Action Macro Automation Engine */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontSize: '11px', color: '#8a99ad', fontWeight: 700, letterSpacing: '0.5px' }}>
            AUTOMATION MACRO RECORDER
          </span>
          <button
            onClick={isRecordingMacro ? onStopMacroRecord : onStartMacroRecord}
            style={{
              backgroundColor: isRecordingMacro ? '#ff0055' : '#00ff8822',
              border: `1px solid ${isRecordingMacro ? '#ff0055' : '#00ff88'}`,
              color: isRecordingMacro ? '#fff' : '#00ff88',
              borderRadius: '8px',
              padding: '4px 12px',
              fontSize: '11px',
              fontWeight: 700,
              cursor: 'pointer'
            }}
          >
            {isRecordingMacro ? '⏹ STOP RECORDING' : '● RECORD MACRO'}
          </button>
        </div>

        {/* Saved Macros List */}
        {savedMacros.length > 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', maxHeight: '140px', overflowY: 'auto' }}>
            {savedMacros.map(macro => (
              <div
                key={macro.id}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  backgroundColor: '#121620',
                  padding: '8px 12px',
                  borderRadius: '8px',
                  border: '1px solid #1c2331'
                }}
              >
                <div>
                  <div style={{ fontSize: '12px', fontWeight: 700, color: '#f0f4f8' }}>{macro.name}</div>
                  <div style={{ fontSize: '10px', color: '#8a99ad' }}>{macro.steps.length} steps · {Math.round(macro.totalDurationMs / 1000)}s</div>
                </div>
                <div style={{ display: 'flex', gap: '6px' }}>
                  <button
                    onClick={() => onPlayMacro(macro)}
                    disabled={isExecuting}
                    style={{
                      backgroundColor: '#00ff88',
                      color: '#000',
                      border: 'none',
                      borderRadius: '6px',
                      padding: '4px 10px',
                      fontSize: '11px',
                      fontWeight: 700,
                      cursor: isExecuting ? 'not-allowed' : 'pointer'
                    }}
                  >
                    ▶ PLAY
                  </button>
                  {onDeleteMacro && (
                    <button
                      onClick={() => onDeleteMacro(macro.id)}
                      style={{
                        backgroundColor: '#ff005522',
                        color: '#ff0055',
                        border: '1px solid #ff005566',
                        borderRadius: '6px',
                        padding: '4px 8px',
                        fontSize: '11px',
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
