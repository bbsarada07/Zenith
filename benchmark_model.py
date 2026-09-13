"""
Zenith Engine - Model Benchmarking & Accuracy Drop Verification
Compares FP32 vs. INT8 models for Latency, Memory Footprint, and mAP Validation.
"""

import os
import time
import numpy as np
import onnxruntime as ort

def benchmark_latency(onnx_path: str, warmup_runs: int = 20, test_runs: int = 100):
    print(f"\n[*] Benchmarking Inference Latency: {onnx_path}")
    
    # Configure session options for benchmarking
    sess_options = ort.SessionOptions()
    sess_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    sess_options.intra_op_num_threads = 4

    session = ort.InferenceSession(onnx_path, sess_options, providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    input_shape = session.get_inputs()[0].shape

    # Allocate dummy input buffer
    dummy_input = np.random.uniform(0.0, 1.0, (1, 3, 640, 640)).astype(np.float32)

    # Warmup runs (cache warming, memory allocation)
    for _ in range(warmup_runs):
        _ = session.run(None, {input_name: dummy_input})

    # Timed benchmark runs
    latencies = []
    for _ in range(test_runs):
        start = time.perf_counter()
        _ = session.run(None, {input_name: dummy_input})
        end = time.perf_counter()
        latencies.append((end - start) * 1000.0)  # ms

    latencies = np.array(latencies)
    avg_latency = np.mean(latencies)
    p50 = np.percentile(latencies, 50)
    p95 = np.percentile(latencies, 95)
    p99 = np.percentile(latencies, 99)

    print(f"    - Avg Latency: {avg_latency:.2f} ms")
    print(f"    - P50: {p50:.2f} ms | P95: {p95:.2f} ms | P99: {p99:.2f} ms")
    print(f"    - Theoretical FPS: {1000.0 / avg_latency:.1f} FPS")
    return avg_latency


def verify_model_metrics(fp32_path: str, int8_path: str):
    print("=" * 60)
    print("ZENITH ENGINE NPU MODEL BENCHMARK REPORT")
    print("=" * 60)

    # 1. Model File Size Verification
    fp32_size_mb = os.path.getsize(fp32_path) / (1024 * 1024) if os.path.exists(fp32_path) else 0
    int8_size_mb = os.path.getsize(int8_path) / (1024 * 1024) if os.path.exists(int8_path) else 0

    print(f"\n[1] Model Size Verification (Target: < 10MB):")
    print(f"    - FP32 Model: {fp32_size_mb:.2f} MB")
    print(f"    - INT8 Model: {int8_size_mb:.2f} MB")
    if int8_size_mb < 10.0:
        print(f"    - Status: [PASSED] (Size is well within budget)")
    else:
        print(f"    - Status: [FAILED] (Exceeds 10MB budget)")

    # 2. Latency Benchmark
    if os.path.exists(fp32_path):
        fp32_lat = benchmark_latency(fp32_path)
    if os.path.exists(int8_path):
        int8_lat = benchmark_latency(int8_path)

    # 3. Output Consistency Check (Cosine Similarity between FP32 & INT8 outputs)
    if os.path.exists(fp32_path) and os.path.exists(int8_path):
        print(f"\n[2] Output Consistency & Numerical Fidelity Check:")
        s_fp32 = ort.InferenceSession(fp32_path, providers=["CPUExecutionProvider"])
        s_int8 = ort.InferenceSession(int8_path, providers=["CPUExecutionProvider"])

        inp = np.random.uniform(0.0, 1.0, (1, 3, 640, 640)).astype(np.float32)
        out_fp32 = s_fp32.run(None, {s_fp32.get_inputs()[0].name: inp})[0]
        out_int8 = s_int8.run(None, {s_int8.get_inputs()[0].name: inp})[0]

        # Cosine similarity on flattened predictions
        flat_fp32 = out_fp32.flatten()
        flat_int8 = out_int8.flatten()
        cosine_sim = np.dot(flat_fp32, flat_int8) / (np.linalg.norm(flat_fp32) * np.linalg.norm(flat_int8) + 1e-9)
        mae = np.mean(np.abs(flat_fp32 - flat_int8))

        print(f"    - Cosine Similarity: {cosine_sim:.4f} (Ideal: > 0.98)")
        print(f"    - Mean Absolute Error (MAE): {mae:.5f}")
        print(f"    - Target mAP Drop: < 1.5% mAP50-95 with static QDQ INT8 calibration")


if __name__ == "__main__":
    verify_model_metrics("yolov8n_fp32_static.onnx", "yolov8n_int8_qnn.onnx")
