/**
 * Zenith AI: TypeScript Controller & DAG Planner with Zod Validation.
 */

import { WebSocketServer, WebSocket } from 'ws';
import { z } from 'zod';

export const CommandActionSchema = z.enum([
  'CLICK_TEXT',
  'CLICK_COORD',
  'SWIPE',
  'TYPE_TEXT',
  'INPUT_KEY',
  'REQUIRE_USER_CONFIRMATION',
  'PING',
  'PONG',
  'AGENT_REGISTER'
]);

export type CommandAction = z.infer<typeof CommandActionSchema>;

export const InboundTelemetrySchema = z.object({
  status: z.enum([
    'SUCCESS',
    'NODE_NOT_FOUND',
    'NO_MUTATION',
    'TIMEOUT',
    'REQUIRE_USER_CONFIRMATION',
    'CONFIRMATION_APPROVED',
    'CONFIRMATION_REJECTED',
    'EMERGENCY_STOP'
  ]).optional(),
  type: z.string().optional(),
  step_id: z.string().optional(),
  current_app: z.string().optional(),
  node_tree_hash: z.string().optional(),
  screen_width: z.number().optional(),
  screen_height: z.number().optional(),
  screen_base64: z.string().optional(),
  error_message: z.string().optional(),
  timestamp: z.number().optional()
});

export type InboundTelemetry = z.infer<typeof InboundTelemetrySchema>;

export interface PlanStep {
  step_id: string;
  action: CommandAction;
  target?: string;
  coords?: [number, number];
  end_coords?: [number, number];
  payload?: string;
  description?: string;
  isSensitive?: boolean;
}

const SENSITIVE_KEYWORDS = [
  'pay', 'payment', 'transfer', 'confirm order', 'delete',
  'reset', 'password', 'pin', 'biometric', 'buy now', 'send money'
];

export class ZenithPlannerController {
  private port: number;
  private wss: WebSocketServer;
  private activeExecutorSocket: WebSocket | null = null;
  private currentPlan: PlanStep[] = [];
  private currentStepIndex: number = 0;
  private isExecuting: boolean = false;
  private retryCountMap: Map<string, number> = new Map();

  constructor(port: number = 8080) {
    this.port = port;
    this.wss = new WebSocketServer({ port });
    this.initServer();
  }

  private initServer(): void {
    console.log(`[ZenithPlanner TS] Initializing Controller Server on port ${this.port}...`);

    this.wss.on('connection', (ws: WebSocket) => {
      console.log(`[ZenithPlanner TS] Client connected.`);

      ws.on('message', (message: Buffer) => {
        try {
          const raw = JSON.parse(message.toString());
          this.handleInboundMessage(ws, raw);
        } catch (err: any) {
          console.error(`[ZenithPlanner TS] Parse error: ${err.message}`);
        }
      });

      ws.on('close', () => {
        if (this.activeExecutorSocket === ws) {
          this.activeExecutorSocket = null;
          this.isExecuting = false;
        }
      });
    });
  }

  private handleInboundMessage(ws: WebSocket, raw: any): void {
    const parseResult = InboundTelemetrySchema.safeParse(raw);
    const telemetry: InboundTelemetry = raw;

    if (telemetry.type === 'AGENT_REGISTER') {
      this.activeExecutorSocket = ws;
      console.log(`[ZenithPlanner TS] Registered Android Executor.`);
      return;
    }

    if (telemetry.type === 'PING') {
      ws.send(JSON.stringify({ type: 'PONG', timestamp: Date.now() }));
      return;
    }

    if (telemetry.status === 'CONFIRMATION_APPROVED') {
      this.executeNextStep();
      return;
    }

    if (telemetry.status === 'CONFIRMATION_REJECTED' || telemetry.status === 'EMERGENCY_STOP') {
      this.isExecuting = false;
      return;
    }

    if (telemetry.status === 'SUCCESS') {
      this.currentStepIndex++;
      this.executeNextStep();
    } else if (telemetry.status === 'NO_MUTATION') {
      this.handleReflectionNoMutation(telemetry);
    }
  }

  public generatePlan(userGoal: string): void {
    this.currentPlan = [
      {
        step_id: 'step_1',
        action: 'CLICK_TEXT',
        target: userGoal,
        description: `Navigate to ${userGoal}`
      }
    ];
    this.currentStepIndex = 0;
  }

  public startExecution(): void {
    if (!this.activeExecutorSocket) return;
    this.isExecuting = true;
    this.executeNextStep();
  }

  private executeNextStep(): void {
    if (!this.isExecuting || this.currentStepIndex >= this.currentPlan.length) {
      this.isExecuting = false;
      return;
    }

    const step = this.currentPlan[this.currentStepIndex];
    if (this.checkSafetyGuardrails(step)) {
      this.sendToExecutor({
        action: 'REQUIRE_USER_CONFIRMATION',
        step_id: step.step_id,
        payload: `Action '${step.description}' is sensitive.`
      });
      return;
    }

    this.sendToExecutor(step);
  }

  private checkSafetyGuardrails(step: PlanStep): boolean {
    if (step.isSensitive) return true;
    const text = `${step.target || ''} ${step.payload || ''} ${step.description || ''}`.toLowerCase();
    return SENSITIVE_KEYWORDS.some(kw => text.includes(kw));
  }

  private handleReflectionNoMutation(telemetry: InboundTelemetry): void {
    const stepId = telemetry.step_id || 'unknown';
    const retries = (this.retryCountMap.get(stepId) || 0) + 1;
    this.retryCountMap.set(stepId, retries);

    if (retries > 3) {
      this.isExecuting = false;
      return;
    }

    const currentStep = this.currentPlan[this.currentStepIndex];
    const revisedStep: PlanStep = {
      ...currentStep,
      step_id: `${stepId}_retry_${retries}`,
      action: 'CLICK_COORD',
      coords: [0.5, 0.5]
    };

    setTimeout(() => this.sendToExecutor(revisedStep), 500);
  }

  private sendToExecutor(packet: any): void {
    if (this.activeExecutorSocket && this.activeExecutorSocket.readyState === WebSocket.OPEN) {
      this.activeExecutorSocket.send(JSON.stringify(packet));
    }
  }
}
