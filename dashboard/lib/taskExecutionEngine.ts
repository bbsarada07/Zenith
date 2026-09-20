import { ActionStep, ActionStepSchema, TargetStructure, BoundingBox } from '@/lib/schemas/actionSchema';

export interface ExecutionResult {
  stepId: string;
  action: string;
  success: boolean;
  retriesAttempted: number;
  statusCode: number;
  latencyMs: number;
  errorMessage?: string;
}

export interface TaskEngineCallbacks {
  onStepStart?: (step: ActionStep, index: number, total: number) => void;
  onStepComplete?: (step: ActionStep, result: ExecutionResult) => void;
  onPlanComplete?: (success: boolean, summary: string) => void;
  onRescanRequested?: () => Promise<BoundingBox[]>;
  onAuditLog?: (command: string, targetBBox: string | undefined, statusCode: number, latencyMs: number, status: 'SUCCESS' | 'RETRY' | 'FAILED') => void;
}

export class TaskExecutionEngine {
  private bridgeHost: string;
  private bridgePort: number;
  private sendWsCommand?: (cmd: Record<string, any>) => void;
  private callbacks: TaskEngineCallbacks;
  private isExecuting: boolean = false;
  private shouldAbort: boolean = false;

  constructor(
    bridgeHost: string = '192.168.1.3',
    bridgePort: number = 8080,
    sendWsCommand?: (cmd: Record<string, any>) => void,
    callbacks: TaskEngineCallbacks = {}
  ) {
    this.bridgeHost = bridgeHost;
    this.bridgePort = bridgePort;
    this.sendWsCommand = sendWsCommand;
    this.callbacks = callbacks;
  }

  public setEndpoint(host: string, port: number = 8080): void {
    this.bridgeHost = host;
    this.bridgePort = port;
  }

  public abort(): void {
    this.shouldAbort = true;
    this.isExecuting = false;
  }

  /**
   * Translates natural language prompt into a structured DAG ActionStep tree.
   */
  public parsePromptToPlan(prompt: string, visibleBoxes: BoundingBox[] = []): ActionStep[] {
    const p = prompt.trim();
    const lower = p.toLowerCase();
    const steps: ActionStep[] = [];

    // Check if target matches visible bounding box
    const matchedBox = visibleBoxes.find(b => 
      b.label.toLowerCase().includes(lower) || lower.includes(b.label.toLowerCase())
    );

    if (matchedBox) {
      const normX = Math.round((matchedBox.x + matchedBox.width / 2) * (1000 / (matchedBox.x > 100 ? 1080 : 1)));
      const normY = Math.round((matchedBox.y + matchedBox.height / 2) * (1000 / (matchedBox.y > 100 ? 2400 : 1)));

      steps.push(ActionStepSchema.parse({
        id: `step_click_${matchedBox.id}`,
        action: 'CLICK',
        target: {
          x: Math.min(1000, Math.max(0, normX)),
          y: Math.min(1000, Math.max(0, normY)),
          label: matchedBox.label,
          confidence: matchedBox.confidence ?? 1.0
        },
        payload: '',
        description: `Click "${matchedBox.label}" at (${normX}, ${normY})`,
        timeoutMs: 4000,
        expectedMutation: true
      }));
      return steps;
    }

    // Pattern Matching for Multi-Step Workflows
    if (lower.includes('search') || lower.startsWith('type ') || lower.startsWith('find ')) {
      const searchTerm = p.replace(/^(search for|search|type|find)\s*/i, '').trim();
      
      // Step 1: Click Search Bar / Input Field
      steps.push(ActionStepSchema.parse({
        id: 'step_1_focus_search',
        action: 'CLICK',
        target: {
          x: 500,
          y: 120,
          label: 'Search Field',
          confidence: 0.95
        },
        payload: '',
        description: 'Focus Search Bar',
        timeoutMs: 3000,
        expectedMutation: false
      }));

      // Step 2: Inject text
      steps.push(ActionStepSchema.parse({
        id: 'step_2_type_query',
        action: 'TYPE',
        payload: searchTerm,
        description: `Input query "${searchTerm}"`,
        timeoutMs: 3000,
        expectedMutation: true
      }));
    } else if (lower.includes('scroll down') || lower.includes('swipe down')) {
      steps.push(ActionStepSchema.parse({
        id: 'step_swipe_down',
        action: 'SWIPE',
        swipe: {
          startX: 500,
          startY: 750,
          endX: 500,
          endY: 250,
          durationMs: 300
        },
        payload: '',
        description: 'Scroll Down',
        timeoutMs: 3000,
        expectedMutation: true
      }));
    } else if (lower.includes('scroll up') || lower.includes('swipe up')) {
      steps.push(ActionStepSchema.parse({
        id: 'step_swipe_up',
        action: 'SWIPE',
        swipe: {
          startX: 500,
          startY: 250,
          endX: 500,
          endY: 750,
          durationMs: 300
        },
        payload: '',
        description: 'Scroll Up',
        timeoutMs: 3000,
        expectedMutation: true
      }));
    } else if (lower.includes('go home') || lower === 'home') {
      steps.push(ActionStepSchema.parse({
        id: 'step_nav_home',
        action: 'GO_HOME',
        payload: '',
        description: 'Navigate to Home Screen',
        timeoutMs: 2000,
        expectedMutation: true
      }));
    } else if (lower.includes('go back') || lower === 'back') {
      steps.push(ActionStepSchema.parse({
        id: 'step_nav_back',
        action: 'GO_BACK',
        payload: '',
        description: 'Navigate Back',
        timeoutMs: 2000,
        expectedMutation: true
      }));
    } else if (lower.includes('recents') || lower.includes('app switch')) {
      steps.push(ActionStepSchema.parse({
        id: 'step_nav_recents',
        action: 'RECENTS',
        payload: '',
        description: 'Open Recent Apps Overview',
        timeoutMs: 2000,
        expectedMutation: true
      }));
    } else {
      // Default: Semantic Text Grounding Target
      steps.push(ActionStepSchema.parse({
        id: 'step_semantic_target',
        action: 'CLICK',
        target: {
          x: 500,
          y: 500,
          label: p,
          confidence: 0.8
        },
        payload: p,
        description: `Ground and Tap "${p}"`,
        timeoutMs: 4000,
        expectedMutation: true
      }));
    }

    return steps;
  }

  /**
   * Executes a sequential DAG ActionStep array with closed-loop retry logic.
   */
  public async executePlan(steps: ActionStep[]): Promise<boolean> {
    if (this.isExecuting || steps.length === 0) return false;

    this.isExecuting = true;
    this.shouldAbort = false;
    let allSucceeded = true;

    for (let i = 0; i < steps.length; i++) {
      if (this.shouldAbort) {
        console.warn('[TaskExecutionEngine] Execution aborted by user.');
        allSucceeded = false;
        break;
      }

      const step = steps[i];
      if (this.callbacks.onStepStart) {
        this.callbacks.onStepStart(step, i + 1, steps.length);
      }

      const result = await this.executeStepWithRetry(step);

      if (this.callbacks.onStepComplete) {
        this.callbacks.onStepComplete(step, result);
      }

      if (!result.success) {
        allSucceeded = false;
        console.error(`[TaskExecutionEngine] Step failed after retries: ${step.id}`);
        break;
      }

      // Small pause between steps for rendering stability
      await new Promise(r => setTimeout(r, 400));
    }

    this.isExecuting = false;
    if (this.callbacks.onPlanComplete) {
      this.callbacks.onPlanComplete(
        allSucceeded,
        allSucceeded ? 'All steps completed successfully.' : 'Execution halted due to step failure.'
      );
    }

    return allSucceeded;
  }

  /**
   * Executes a single ActionStep with up to 2 closed-loop retry attempts.
   */
  private async executeStepWithRetry(step: ActionStep): Promise<ExecutionResult> {
    let retries = 0;
    const maxRetries = 2;
    let lastError = '';
    const startTime = performance.now();

    while (retries <= maxRetries) {
      if (this.shouldAbort) {
        return {
          stepId: step.id,
          action: step.action,
          success: false,
          retriesAttempted: retries,
          statusCode: 499,
          latencyMs: Math.round(performance.now() - startTime),
          errorMessage: 'Aborted by user'
        };
      }

      const isRetry = retries > 0;
      if (isRetry) {
        console.warn(`[TaskExecutionEngine] Closed-Loop Retry #${retries} for step '${step.id}'...`);
        if (this.callbacks.onAuditLog) {
          this.callbacks.onAuditLog(
            `RETRY_${step.action}`,
            step.target?.label || undefined,
            202,
            Math.round(performance.now() - startTime),
            'RETRY'
          );
        }

        // Trigger screen re-scan if callback provided
        if (this.callbacks.onRescanRequested) {
          try {
            await this.callbacks.onRescanRequested();
          } catch (_) {}
        }
        await new Promise(r => setTimeout(r, 500));
      }

      try {
        const success = await this.dispatchSingleAction(step, isRetry);
        const latency = Math.round(performance.now() - startTime);

        if (success) {
          if (this.callbacks.onAuditLog) {
            this.callbacks.onAuditLog(
              step.action,
              step.target?.label || (step.target ? `(${step.target.x},${step.target.y})` : undefined),
              200,
              latency,
              'SUCCESS'
            );
          }

          return {
            stepId: step.id,
            action: step.action,
            success: true,
            retriesAttempted: retries,
            statusCode: 200,
            latencyMs: latency
          };
        } else {
          lastError = 'Action returned false from bridge.';
        }
      } catch (err: any) {
        lastError = err.message || 'Network dispatch error';
      }

      retries++;
    }

    const totalLatency = Math.round(performance.now() - startTime);
    if (this.callbacks.onAuditLog) {
      this.callbacks.onAuditLog(
        step.action,
        step.target?.label || undefined,
        500,
        totalLatency,
        'FAILED'
      );
    }

    return {
      stepId: step.id,
      action: step.action,
      success: false,
      retriesAttempted: retries - 1,
      statusCode: 500,
      latencyMs: totalLatency,
      errorMessage: lastError
    };
  }

  /**
   * Dispatches the action via REST endpoint `POST http://${bridgeHost}:${port}/api/v1/execute`
   * with automatic WebSocket fallback.
   */
  private async dispatchSingleAction(step: ActionStep, isRetry: boolean = false): Promise<boolean> {
    // 1. Navigation Actions
    if (step.action === 'GO_HOME' || step.action === 'GO_BACK' || step.action === 'RECENTS') {
      const navTarget = step.action.replace('GO_', '').toLowerCase();
      try {
        const response = await fetch(`http://${this.bridgeHost}:${this.bridgePort}/api/v1/nav`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ action: navTarget }),
          signal: AbortSignal.timeout(3000)
        });
        if (response.ok) return true;
      } catch (_) {
        // Fallback to WebSocket
        if (this.sendWsCommand) {
          this.sendWsCommand({
            action: 'INPUT_KEY',
            target: navTarget,
            key: navTarget.toUpperCase(),
            timestamp: Date.now()
          });
          return true;
        }
      }
      return false;
    }

    // 2. Click / Tap Action
    if (step.action === 'CLICK' || step.action === 'LONG_PRESS') {
      const normX = (step.target?.x ?? 500) / 1000.0;
      const normY = (step.target?.y ?? 500) / 1000.0;

      // In retry mode, apply micro-offset if target was slightly off
      const finalX = isRetry ? Math.min(1.0, normX + 0.02) : normX;
      const finalY = isRetry ? Math.min(1.0, normY + 0.02) : normY;

      const restPayload = {
        action: step.action === 'LONG_PRESS' ? 'LONG_PRESS' : 'CLICK',
        targetText: step.target?.label || step.payload || '',
        coords: [finalX, finalY],
        isRetry
      };

      try {
        const response = await fetch(`http://${this.bridgeHost}:${this.bridgePort}/api/v1/execute`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(restPayload),
          signal: AbortSignal.timeout(step.timeoutMs || 4000)
        });
        if (response.ok) return true;
      } catch (_) {
        // Fallback to WebSocket
        if (this.sendWsCommand) {
          if (step.target?.label) {
            this.sendWsCommand({
              action: 'CLICK_TEXT',
              target: step.target.label,
              coords: [finalX, finalY],
              timestamp: Date.now()
            });
          } else {
            this.sendWsCommand({
              action: 'REMOTE_TAP',
              coords: [finalX, finalY],
              timestamp: Date.now()
            });
          }
          return true;
        }
      }
      return false;
    }

    // 3. Swipe Action
    if (step.action === 'SWIPE') {
      const swipe = step.swipe || { startX: 500, startY: 700, endX: 500, endY: 300, durationMs: 250 };
      const startX = swipe.startX / 1000.0;
      const startY = swipe.startY / 1000.0;
      const endX = swipe.endX / 1000.0;
      const endY = swipe.endY / 1000.0;

      const restPayload = {
        action: 'SWIPE',
        startX,
        startY,
        endX,
        endY,
        durationMs: swipe.durationMs || 250
      };

      try {
        const response = await fetch(`http://${this.bridgeHost}:${this.bridgePort}/api/v1/execute`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(restPayload),
          signal: AbortSignal.timeout(3000)
        });
        if (response.ok) return true;
      } catch (_) {
        if (this.sendWsCommand) {
          this.sendWsCommand({
            action: 'REMOTE_SWIPE',
            startX,
            startY,
            endX,
            endY,
            durationMs: swipe.durationMs || 250,
            timestamp: Date.now()
          });
          return true;
        }
      }
      return false;
    }

    // 4. Type Action
    if (step.action === 'TYPE') {
      try {
        const response = await fetch(`http://${this.bridgeHost}:${this.bridgePort}/api/v1/execute`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ action: 'TYPE', text: step.payload }),
          signal: AbortSignal.timeout(3000)
        });
        if (response.ok) return true;
      } catch (_) {
        if (this.sendWsCommand) {
          this.sendWsCommand({
            action: 'INPUT_TEXT',
            payload: step.payload,
            text: step.payload,
            timestamp: Date.now()
          });
          return true;
        }
      }
      return false;
    }

    return false;
  }
}
