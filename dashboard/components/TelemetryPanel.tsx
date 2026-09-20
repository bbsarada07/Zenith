import React from 'react';
import { ActionAuditLog } from '@/lib/schemas/actionSchema';

export interface TelemetryPanelProps {
  bridgeStatus: 'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED';
  bridgeProtocol?: string;
  fps: number;
  frameLatencyMs: number;
  npuInferenceTimeMs: number;
  npuUsagePercent: number;
  confidenceMatrix: number;
  executionProvider: string;
  heapMemoryMb: number;
  activeApp: string;
  auditLogs: ActionAuditLog[];
  showBoundingBoxes: boolean;
  setShowBoundingBoxes: (val: boolean) => void;
  showTargetOffsets: boolean;
  setShowTargetOffsets: (val: boolean) => void;
  enablePrivacyMasking: boolean;
  setEnablePrivacyMasking: (val: boolean) => void;
  onClearLogs?: () => void;
}

export const TelemetryPanel: React.FC<TelemetryPanelProps> = ({
  bridgeStatus,
  bridgeProtocol = 'iQOO Office Kit Bridge (Active)',
  fps,
  frameLatencyMs,
  npuInferenceTimeMs,
  npuUsagePercent,
  confidenceMatrix,
  executionProvider,
  heapMemoryMb,
  activeApp,
  auditLogs,
  showBoundingBoxes,
  setShowBoundingBoxes,
  showTargetOffsets,
  setShowTargetOffsets,
  enablePrivacyMasking,
  setEnablePrivacyMasking,
  onClearLogs
}) => {
  const getStatusColor = (status: string) => {
    switch (status) {
      case 'CONNECTED': return '#00FF66';
      case 'RECONNECTING': return '#FFB800';
      case 'DISCONNECTED': return '#FF2E54';
      default: return '#8a99ad';
    }
  };

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: '14px',
        height: '100%',
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
      {/* HUD Header with Bridge Protocol Badge */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <div
            style={{
              width: '10px',
              height: '10px',
              borderRadius: '50%',
              backgroundColor: getStatusColor(bridgeStatus),
              boxShadow: `0 0 12px ${getStatusColor(bridgeStatus)}`
            }}
          />
          <span style={{ fontSize: '13px', fontWeight: 900, letterSpacing: '0.8px', color: '#00F0FF', fontFamily: 'monospace' }}>
            HARDWARE HUD TELEMETRY
          </span>
        </div>
        <span
          style={{
            fontSize: '11px',
            fontWeight: 800,
            padding: '3px 10px',
            borderRadius: '12px',
            backgroundColor: `${getStatusColor(bridgeStatus)}18`,
            color: getStatusColor(bridgeStatus),
            border: `1px solid ${getStatusColor(bridgeStatus)}55`,
            fontFamily: 'monospace'
          }}
        >
          {bridgeStatus}
        </span>
      </div>

      {/* Protocol Identifier */}
      <div
        style={{
          backgroundColor: '#070A12',
          padding: '8px 12px',
          borderRadius: '8px',
          border: '1px solid rgba(0, 240, 255, 0.15)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          fontSize: '11px',
          fontFamily: 'monospace'
        }}
      >
        <span style={{ color: '#8A99AD' }}>BRIDGE PROTOCOL:</span>
        <span style={{ color: '#00FF66', fontWeight: 700 }}>{bridgeProtocol}</span>
      </div>

      {/* 4-Card Hardware Metric Matrix */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(2, 1fr)',
          gap: '10px'
        }}
      >
        {/* NPU Latency & Execution Provider */}
        <div
          style={{
            backgroundColor: '#090D18',
            borderRadius: '10px',
            padding: '12px',
            border: '1px solid rgba(0, 240, 255, 0.18)',
            boxShadow: 'inset 0 0 10px rgba(0, 240, 255, 0.02)'
          }}
        >
          <div style={{ fontSize: '10px', color: '#8A99AD', fontWeight: 700, fontFamily: 'monospace' }}>
            NPU LATENCY (QNN)
          </div>
          <div style={{ fontSize: '22px', fontWeight: 900, color: '#00F0FF', marginTop: '4px', fontFamily: 'monospace' }}>
            {npuInferenceTimeMs.toFixed(1)} <span style={{ fontSize: '11px', color: '#8A99AD' }}>ms</span>
          </div>
          <div style={{ fontSize: '9px', color: '#00FF66', marginTop: '2px', fontFamily: 'monospace', textOverflow: 'ellipsis', overflow: 'hidden', whiteSpace: 'nowrap' }}>
            {executionProvider}
          </div>
        </div>

        {/* Spatial Render Latency */}
        <div
          style={{
            backgroundColor: '#090D18',
            borderRadius: '10px',
            padding: '12px',
            border: '1px solid rgba(0, 240, 255, 0.18)'
          }}
        >
          <div style={{ fontSize: '10px', color: '#8A99AD', fontWeight: 700, fontFamily: 'monospace' }}>
            SPATIAL LATENCY
          </div>
          <div style={{ fontSize: '22px', fontWeight: 900, color: frameLatencyMs <= 50 ? '#00FF66' : '#FFB800', marginTop: '4px', fontFamily: 'monospace' }}>
            {frameLatencyMs} <span style={{ fontSize: '11px', color: '#8A99AD' }}>ms</span>
          </div>
          <div style={{ fontSize: '9px', color: '#8A99AD', marginTop: '2px', fontFamily: 'monospace' }}>
            TARGET: &lt; 50ms ({fps} FPS)
          </div>
        </div>

        {/* NPU Load Utilization */}
        <div
          style={{
            backgroundColor: '#090D18',
            borderRadius: '10px',
            padding: '12px',
            border: '1px solid rgba(0, 240, 255, 0.18)'
          }}
        >
          <div style={{ fontSize: '10px', color: '#8A99AD', fontWeight: 700, fontFamily: 'monospace' }}>
            NPU UTILIZATION
          </div>
          <div style={{ fontSize: '22px', fontWeight: 900, color: '#00F0FF', marginTop: '4px', fontFamily: 'monospace' }}>
            {npuUsagePercent.toFixed(1)}%
          </div>
          <div style={{ fontSize: '9px', color: '#8A99AD', marginTop: '2px', fontFamily: 'monospace' }}>
            INT8 Snapdragon Tensor Load
          </div>
        </div>

        {/* Zod Validation Confidence Matrix */}
        <div
          style={{
            backgroundColor: '#090D18',
            borderRadius: '10px',
            padding: '12px',
            border: '1px solid rgba(0, 240, 255, 0.18)'
          }}
        >
          <div style={{ fontSize: '10px', color: '#8A99AD', fontWeight: 700, fontFamily: 'monospace' }}>
            CONFIDENCE MATRIX
          </div>
          <div style={{ fontSize: '22px', fontWeight: 900, color: confidenceMatrix >= 0.70 ? '#00FF66' : '#FFB800', marginTop: '4px', fontFamily: 'monospace' }}>
            {confidenceMatrix.toFixed(2)}
          </div>
          <div style={{ fontSize: '9px', color: '#8A99AD', marginTop: '2px', fontFamily: 'monospace' }}>
            Zod Verified Score
          </div>
        </div>
      </div>

      {/* Heap Memory & Foreground App Package */}
      <div
        style={{
          backgroundColor: '#070A12',
          padding: '10px 12px',
          borderRadius: '8px',
          border: '1px solid rgba(0, 240, 255, 0.15)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          fontSize: '11px',
          fontFamily: 'monospace'
        }}
      >
        <div style={{ display: 'flex', gap: '6px' }}>
          <span style={{ color: '#8A99AD' }}>HEAP:</span>
          <span style={{ color: '#FFFFFF', fontWeight: 700 }}>{heapMemoryMb} MB</span>
        </div>
        <div style={{ display: 'flex', gap: '6px' }}>
          <span style={{ color: '#8A99AD' }}>APP:</span>
          <span style={{ color: '#00F0FF', fontWeight: 700 }}>
            {activeApp.length > 20 ? activeApp.slice(-18) : activeApp || 'system'}
          </span>
        </div>
      </div>

      {/* Viewport Overlay Controls */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
        <div style={{ fontSize: '10px', color: '#8A99AD', fontWeight: 800, letterSpacing: '0.6px', fontFamily: 'monospace' }}>
          VIEWPORT MODES
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
          <label
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              backgroundColor: '#090D18',
              padding: '8px 12px',
              borderRadius: '6px',
              cursor: 'pointer',
              border: '1px solid rgba(0, 240, 255, 0.15)',
              fontSize: '11px',
              fontFamily: 'monospace'
            }}
          >
            <span>[Show Bounding Boxes]</span>
            <input
              type="checkbox"
              checked={showBoundingBoxes}
              onChange={e => setShowBoundingBoxes(e.target.checked)}
              style={{ accentColor: '#00F0FF', cursor: 'pointer' }}
            />
          </label>

          <label
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              backgroundColor: '#090D18',
              padding: '8px 12px',
              borderRadius: '6px',
              cursor: 'pointer',
              border: '1px solid rgba(0, 240, 255, 0.15)',
              fontSize: '11px',
              fontFamily: 'monospace'
            }}
          >
            <span>[Show Target Offsets]</span>
            <input
              type="checkbox"
              checked={showTargetOffsets}
              onChange={e => setShowTargetOffsets(e.target.checked)}
              style={{ accentColor: '#00FF66', cursor: 'pointer' }}
            />
          </label>

          <label
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              backgroundColor: '#090D18',
              padding: '8px 12px',
              borderRadius: '6px',
              cursor: 'pointer',
              border: '1px solid rgba(0, 240, 255, 0.15)',
              fontSize: '11px',
              fontFamily: 'monospace'
            }}
          >
            <span>[Privacy Masking]</span>
            <input
              type="checkbox"
              checked={enablePrivacyMasking}
              onChange={e => setEnablePrivacyMasking(e.target.checked)}
              style={{ accentColor: '#FF2E54', cursor: 'pointer' }}
            />
          </label>
        </div>
      </div>

      {/* Chronological Action Audit Stream */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', flex: 1, minHeight: '190px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontSize: '10px', color: '#8A99AD', fontWeight: 800, letterSpacing: '0.6px', fontFamily: 'monospace' }}>
            CHRONOLOGICAL ACTION AUDIT FEED
          </span>
          {onClearLogs && (
            <button
              onClick={onClearLogs}
              style={{
                backgroundColor: 'transparent',
                border: 'none',
                color: '#8A99AD',
                fontSize: '10px',
                cursor: 'pointer',
                fontFamily: 'monospace',
                textDecoration: 'underline'
              }}
            >
              Clear
            </button>
          )}
        </div>

        <div
          style={{
            flex: 1,
            backgroundColor: '#05070D',
            borderRadius: '8px',
            border: '1px solid rgba(0, 240, 255, 0.18)',
            padding: '8px',
            overflowY: 'auto',
            display: 'flex',
            flexDirection: 'column',
            gap: '4px',
            fontFamily: 'monospace',
            fontSize: '10px'
          }}
        >
          {auditLogs.length === 0 ? (
            <div style={{ color: '#3A485A', textAlign: 'center', margin: 'auto' }}>
              Awaiting action executions...
            </div>
          ) : (
            auditLogs.slice().reverse().map(log => (
              <div
                key={log.id}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  padding: '3px 6px',
                  backgroundColor: '#080C16',
                  borderRadius: '4px',
                  borderLeft: `3px solid ${log.status === 'SUCCESS' ? '#00FF66' : log.status === 'RETRY' ? '#FFB800' : '#FF2E54'}`
                }}
              >
                <div style={{ display: 'flex', gap: '6px', overflow: 'hidden', whiteSpace: 'nowrap' }}>
                  <span style={{ color: '#4A5B70' }}>[{log.timestamp}]</span>
                  <span style={{ color: '#8A99AD' }}>[{log.channel}]</span>
                  <span style={{ color: '#00F0FF', fontWeight: 700 }}>{log.command}</span>
                  {log.targetCoords && (
                    <span style={{ color: '#00FF66' }}>{log.targetCoords}</span>
                  )}
                  <span style={{ color: '#FFB800' }}>({log.confidence.toFixed(2)})</span>
                </div>
                <div style={{ display: 'flex', gap: '6px', alignItems: 'center' }}>
                  <span style={{ color: '#4A5B70' }}>{log.latencyMs}ms</span>
                  <span style={{ color: log.status === 'SUCCESS' ? '#00FF66' : log.status === 'RETRY' ? '#FFB800' : '#FF2E54', fontWeight: 800 }}>
                    {log.statusCode === 200 ? '200 OK' : log.statusCode === 202 ? '202 RETRY' : `${log.statusCode} ERR`}
                  </span>
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};
