import { z } from 'zod';

/**
 * Strict Action Type Definitions
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
 * Target Coordinate & Grounding Schema
 */
export const TargetStructureSchema = z.object({
  x: z.number().min(0).max(1000),
  y: z.number().min(0).max(1000),
  label: z.string().default(''),
  confidence: z.number().min(0).max(1).default(1.0)
});

export type TargetStructure = z.infer<typeof TargetStructureSchema>;

/**
 * Swipe Coordinates Schema
 */
export const SwipeParamsSchema = z.object({
  startX: z.number().min(0).max(1000),
  startY: z.number().min(0).max(1000),
  endX: z.number().min(0).max(1000),
  endY: z.number().min(0).max(1000),
  durationMs: z.number().min(50).max(3000).default(250)
});

export type SwipeParams = z.infer<typeof SwipeParamsSchema>;

/**
 * Action Tree Step Execution Schema
 */
export const ActionStepSchema = z.object({
  id: z.string(),
  action: ActionTypeSchema,
  target: TargetStructureSchema.optional(),
  swipe: SwipeParamsSchema.optional(),
  payload: z.string().default(''),
  description: z.string().default(''),
  timeoutMs: z.number().default(5000),
  expectedMutation: z.boolean().default(true)
});

export type ActionStep = z.infer<typeof ActionStepSchema>;

/**
 * Bounding Box (BBox) Grounding Schema
 */
export const BoundingBoxSchema = z.object({
  id: z.string(),
  x: z.number(), // Normalized or physical
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
 * Device Physical Navigation Schema
 */
export const NavActionSchema = z.object({
  action: z.enum(['HOME', 'BACK', 'RECENTS', 'NOTIFICATIONS', 'QUICK_SETTINGS']),
  timestamp: z.number().default(() => Date.now())
});

export type NavAction = z.infer<typeof NavActionSchema>;

/**
 * Live Telemetry Frame Schema
 */
export const TelemetryFrameSchema = z.object({
  fps: z.number().min(0).max(120),
  latencyMs: z.number().min(0),
  npuUsagePercent: z.number().min(0).max(100),
  heapMemoryMb: z.number().min(0),
  bridgeStatus: z.enum(['CONNECTED', 'RECONNECTING', 'DISCONNECTED']),
  screenWidth: z.number().default(1080),
  screenHeight: z.number().default(2400),
  activeApp: z.string().default(''),
  timestamp: z.number()
});

export type TelemetryFrame = z.infer<typeof TelemetryFrameSchema>;

/**
 * Action Audit Log Entry Schema
 */
export const ActionAuditLogSchema = z.object({
  id: z.string(),
  timestamp: z.string(),
  command: string(),
  targetBBox: z.string().optional(),
  statusCode: z.number().default(200),
  latencyMs: z.number().default(0),
  status: z.enum(['SUCCESS', 'RETRY', 'FAILED'])
});

export type ActionAuditLog = z.infer<typeof ActionAuditLogSchema>;

function string() {
  return z.string();
}
