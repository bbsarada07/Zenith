import { z } from 'zod';

/**
 * Strict Spatial Action Zod Schema (iQOO Office Kit & WebSocket Hybrid Transport)
 */
export const SpatialActionSchema = z.object({
  eventId: z.string(),
  timestamp: z.number(),
  action: z.enum([
    'CLICK',
    'LONG_PRESS',
    'SWIPE',
    'TYPE',
    'GO_HOME',
    'GO_BACK',
    'RECENTS'
  ]),
  target: z.object({
    x: z.number().min(0),
    y: z.number().min(0),
    label: z.string()
  }),
  confidence: z.number().min(0).max(1),
  channel: z.enum(['WEBSOCKET_STREAM', 'OFFICE_KIT_SHARED_CLIPBOARD'])
});

export type SpatialAction = z.infer<typeof SpatialActionSchema>;

/**
 * Action Type Schema
 */
export const ActionTypeSchema = z.enum([
  'CLICK',
  'LONG_PRESS',
  'SWIPE',
  'TYPE',
  'GO_HOME',
  'GO_BACK',
  'RECENTS'
]);

export type ActionType = z.infer<typeof ActionTypeSchema>;

/**
 * Target Structure Schema (Supports physical pixels and normalized coords)
 */
export const TargetStructureSchema = z.object({
  x: z.number().min(0),
  y: z.number().min(0),
  label: z.string().default(''),
  confidence: z.number().min(0).max(1).default(1.0)
});

export type TargetStructure = z.infer<typeof TargetStructureSchema>;

/**
 * Swipe Parameters Schema
 */
export const SwipeParamsSchema = z.object({
  startX: z.number().min(0),
  startY: z.number().min(0),
  endX: z.number().min(0),
  endY: z.number().min(0),
  durationMs: z.number().min(50).max(5000).default(250)
});

export type SwipeParams = z.infer<typeof SwipeParamsSchema>;

/**
 * Action Step Schema
 */
export const ActionStepSchema = z.object({
  id: z.string(),
  action: ActionTypeSchema,
  target: TargetStructureSchema.optional(),
  swipe: SwipeParamsSchema.optional(),
  payload: z.string().default(''),
  description: z.string().default(''),
  timeoutMs: z.number().default(5000),
  expectedMutation: z.boolean().default(true),
  confidence: z.number().min(0).max(1).default(1.0),
  stage: z.enum(['PRIMARY', 'FALLBACK_OCR_RESCAN', 'FALLBACK_UI_RECOVERY']).default('PRIMARY')
});

export type ActionStep = z.infer<typeof ActionStepSchema>;

/**
 * Bounding Box Schema
 */
export const BoundingBoxSchema = z.object({
  id: z.string(),
  x: z.number(),
  y: z.number(),
  width: z.number(),
  height: z.number(),
  label: z.string(),
  isClickable: z.boolean().default(true),
  isEditable: z.boolean().default(false),
  confidence: z.number().min(0).max(1).default(1.0),
  resourceId: z.string().optional()
});

export type BoundingBox = z.infer<typeof BoundingBoxSchema>;

/**
 * Live NPU Hardware Telemetry Schema
 */
export const NpuMetricsSchema = z.object({
  npuInferenceTimeMs: z.number().min(0),
  npuUtilizationPct: z.number().min(0).max(100),
  executionProvider: z.string(),
  activeModel: z.string(),
  quantizationPrecision: z.enum(['INT8', 'FP16', 'FP32'])
});

export type NpuMetrics = z.infer<typeof NpuMetricsSchema>;

/**
 * Telemetry Frame Schema
 */
export const TelemetryFrameSchema = z.object({
  fps: z.number().min(0).max(120),
  latencyMs: z.number().min(0),
  npuInferenceTimeMs: z.number().min(0).default(18),
  npuUsagePercent: z.number().min(0).max(100).default(42),
  heapMemoryMb: z.number().min(0),
  bridgeStatus: z.enum(['CONNECTED', 'RECONNECTING', 'DISCONNECTED']),
  bridgeProtocol: z.string().default('iQOO Office Kit Bridge (Active)'),
  executionProvider: z.string().default('Qualcomm QNN Direct Execution'),
  confidenceMatrix: z.number().min(0).max(1).default(0.96),
  screenWidth: z.number().default(1080),
  screenHeight: z.number().default(2400),
  activeApp: z.string().default(''),
  timestamp: z.number()
});

export type TelemetryFrame = z.infer<typeof TelemetryFrameSchema>;

/**
 * Chronological Action Audit Log Schema
 */
export const ActionAuditLogSchema = z.object({
  id: z.string(),
  timestamp: z.string(),
  channel: z.enum(['WEBSOCKET_STREAM', 'OFFICE_KIT_CLIPBOARD', 'LOCAL_NPU_QNN']).default('OFFICE_KIT_CLIPBOARD'),
  command: z.string(),
  targetCoords: z.string().optional(),
  targetBBox: z.string().optional(),
  confidence: z.number().default(0.96),
  statusCode: z.number().default(200),
  latencyMs: z.number().default(0),
  status: z.enum(['SUCCESS', 'RETRY', 'FAILED'])
});

export type ActionAuditLog = z.infer<typeof ActionAuditLogSchema>;
