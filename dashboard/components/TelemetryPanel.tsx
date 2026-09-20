import React from 'react';
import { ActionAuditLog } from '@/lib/schemas/actionSchema';

export interface TelemetryPanelProps {
  bridgeStatus: 'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED';
  fps: number;
  frameLatencyMs: number;
  npuUsagePercent: number;
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
  fps,
  frameLatencyMs,
  npuUsagePercent,
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
      case 'CONNECTED': return '#00ff88';
      case 'RECONNECTING': return '#ffe600';
      case 'DISCONNECTED': return '#ff0055';
      default: return '#8a99ad';
    }
  };

  const getLogStatusBadge = (status: 'SUCCESS' | 'RETRY' | 'FAILED') => {
    switch (status) {
      case 'SUCCESS':
        return <span style={{ color: '#00ff88', fontWeight: 700 }}>200 OK</span>;
      case 'RETRY':
        return <span style={{ color: '#ffe600', fontWeight: 700 }}>202 RETRY</span>;
      case 'FAILED':
        return <span style={{ color: '#ff0055', fontWeight: 700 }}>500 ERR</span>;
    }
  };

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: '16px',
        height: '100%',
        backgroundColor: '#0c0e14',
        borderRadius: '16px',
        border: '1px solid #1f2737',
        padding: '20px',
        color: '#f0f4f8',
        boxShadow: '0 10px 30px rgba(0,0,0,0.5)',
        overflowY: 'auto'
      }}
    >
      {/* Header & Status Indicator */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <div
            style={{
              width: '10px',
              height: '100%',
              minHeight: '10px',
              borderRadius: '50%',
              backgroundColor: getStatusColor(bridgeStatus),
              boxShadow: `0 0 10px ${getStatusColor(bridgeStatus)}`
            }}
          />
          <span style={{ fontSize: '14px', fontWeight: 800, letterSpacing: '0.8px', color: '#f0f4f8' }}>
            ENGINE TELEMETRY
          </span>
        </div>
        <span
          style={{
            fontSize: '11px',
            fontWeight: 700,
            padding: '4px 10px',
            borderRadius: '12px',
            backgroundColor: `${getStatusColor(bridgeStatus)}22`,
            color: getStatusColor(bridgeStatus),
            border: `1px solid ${getStatusColor(bridgeStatus)}66`
          }}
        >
          {bridgeStatus}
        </span>
      </div>

      {/* Metrics Grid */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(2, 1fr)',
          gap: '12px'
        }}
      >
        {/* Stream FPS */}
        <div
          style={{
            backgroundColor: '#121620',
            borderRadius: '12px',
            padding: '12px',
            border: '1px solid #1c2331'
          }}
        >
          <div style={{ fontSize: '11px', color: '#8a99ad', fontWeight: 600 }}>STREAM FPS</div>
          <div style={{ fontSize: '24px', fontWeight: 800, color: fps >= 25 ? '#00ff88' : '#ffe600', marginTop: '4px' }}>
            {fps} <span style={{ fontSize: '12px', color: '#8a99ad', fontWeight: 500 }}>FPS</span>
          </div>
        </div>

        {/* Frame Latency */}
        <div
          style={{
            backgroundColor: '#121620',
            borderRadius: '12px',
            padding: '12px',
            border: '1px solid #1c2331'
          }}
        >
          <div style={{ fontSize: '11px', color: '#8a99ad', fontWeight: 600 }}>RENDER LATENCY</div>
          <div style={{ fontSize: '24px', fontWeight: 800, color: frameLatencyMs <= 40 ? '#00f0ff' : '#ffe600', marginTop: '4px' }}>
            {frameLatencyMs} <span style={{ fontSize: '12px', color: '#8a99ad', fontWeight: 500 }}>ms</span>
          </div>
        </div>

        {/* NPU Utilization */}
        <div
          style={{
            backgroundColor: '#121620',
            borderRadius: '12px',
            padding: '12px',
            border: '1px solid #1c2331'
          }}
        >
          <div style={{ fontSize: '11px', color: '#8a99ad', fontWeight: 600 }}>NPU LOAD</div>
          <div style={{ fontSize: '24px', fontWeight: 800, color: '#00f0ff', marginTop: '4px' }}>
            {npuUsagePercent}%
          </div>
        </div>

        {/* Heap Memory */}
        <div
          style={{
            backgroundColor: '#121620',
            borderRadius: '12px',
            padding: '12px',
            border: '1px solid #1c2331'
          }}
        >
          <div style={{ fontSize: '11px', color: '#8a99ad', fontWeight: 600 }}>HEAP USAGE</div>
          <div style={{ fontSize: '24px', fontWeight: 800, color: '#f0f4f8', marginTop: '4px' }}>
            {heapMemoryMb} <span style={{ fontSize: '12px', color: '#8a99ad', fontWeight: 500 }}>MB</span>
          </div>
        </div>
      </div>

      {/* Active Application Info */}
      <div
        style={{
          backgroundColor: '#121620',
          borderRadius: '12px',
          padding: '12px 14px',
          border: '1px solid #1c2331',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center'
        }}
      >
        <span style={{ fontSize: '11px', color: '#8a99ad', fontWeight: 600 }}>TOP PACKAGE</span>
        <span style={{ fontSize: '12px', fontFamily: 'monospace', color: '#00f0ff', fontWeight: 600 }}>
          {activeApp.length > 26 ? activeApp.slice(-24) : activeApp || 'system'}
        </span>
      </div>

      {/* Spatial Overlay Viewport Toggles */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <div style={{ fontSize: '11px', color: '#8a99ad', fontWeight: 700, letterSpacing: '0.5px' }}>
          VIEWPORT OVERLAY MODES
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
          <label
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              backgroundColor: '#121620',
              padding: '10px 12px',
              borderRadius: '8px',
              cursor: 'pointer',
              border: '1px solid #1c2331'
            }}
          >
            <span style={{ fontSize: '12px', fontWeight: 600 }}>Show Bounding Boxes</span>
            <input
              type="checkbox"
              checked={showBoundingBoxes}
              onChange={e => setShowBoundingBoxes(e.target.checked)}
              style={{ accentColor: '#00f0ff', cursor: 'pointer' }}
            />
          </label>

          <label
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              backgroundColor: '#121620',
              padding: '10px 12px',
              borderRadius: '8px',
              cursor: 'pointer',
              border: '1px solid #1c2331'
            }}
          >
            <span style={{ fontSize: '12px', fontWeight: 600 }}>Show Target Offsets</span>
            <input
              type="checkbox"
              checked={showTargetOffsets}
              onChange={e => setShowTargetOffsets(e.target.checked)}
              style={{ accentColor: '#00ff88', cursor: 'pointer' }}
            />
          </label>

          <label
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              backgroundColor: '#121620',
              padding: '10px 12px',
              borderRadius: '8px',
              cursor: 'pointer',
              border: '1px solid #1c2331'
            }}
          >
            <span style={{ fontSize: '12px', fontWeight: 600 }}>Privacy Masking</span>
            <input
              type="checkbox"
              checked={enablePrivacyMasking}
              onChange={e => setEnablePrivacyMasking(e.target.checked)}
              style={{ accentColor: '#ff0055', cursor: 'pointer' }}
            />
          </label>
        </div>
      </div>

      {/* Action Audit Stream */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', flex: 1, minHeight: '180px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontSize: '11px', color: '#8a99ad', fontWeight: 700, letterSpacing: '0.5px' }}>
            ACTION AUDIT STREAM
          </span>
          {onClearLogs && (
            <button
              onClick={onClearLogs}
              style={{
                backgroundColor: 'transparent',
                border: 'none',
                color: '#8a99ad',
                fontSize: '10px',
                cursor: 'pointer',
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
            backgroundColor: '#07090e',
            borderRadius: '10px',
            border: '1px solid #182232',
            padding: '10px',
            overflowY: 'auto',
            display: 'flex',
            flexDirection: 'column',
            gap: '6px',
            fontFamily: 'monospace',
            fontSize: '11px'
          }}
        >
          {auditLogs.length === 0 ? (
            <div style={{ color: '#445166', textAlign: 'center', margin: 'auto' }}>
              No actions executed yet.
            </div>
          ) : (
            auditLogs.slice().reverse().map(log => (
              <div
                key={log.id}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  padding: '4px 6px',
                  backgroundColor: '#0e131d',
                  borderRadius: '6px',
                  borderLeft: `3px solid ${log.status === 'SUCCESS' ? '#00ff88' : log.status === 'RETRY' ? '#ffe600' : '#ff0055'}`
                }}
              >
                <div style={{ display: 'flex', gap: '6px', overflow: 'hidden' }}>
                  <span style={{ color: '#52667d' }}>[{log.timestamp}]</span>
                  <span style={{ color: '#00f0ff', fontWeight: 600 }}>{log.command}</span>
                  {log.targetBBox && (
                    <span style={{ color: '#8a99ad', textOverflow: 'ellipsis', overflow: 'hidden', whiteSpace: 'nowrap' }}>
                      {log.targetBBox}
                    </span>
                  )}
                </div>
                <div style={{ display: 'flex', gap: '6px', alignItems: 'center' }}>
                  <span style={{ color: '#52667d' }}>{log.latencyMs}ms</span>
                  {getLogStatusBadge(log.status)}
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};
