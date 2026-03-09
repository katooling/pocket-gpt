const gpuElapsedMs = Number(output.gpu_elapsed_ms)
const cpuElapsedMs = Number(output.cpu_elapsed_ms)

if (!Number.isFinite(gpuElapsedMs) || gpuElapsedMs <= 0) {
  throw new Error(`Invalid GPU elapsed time: ${output.gpu_elapsed_ms}`)
}

if (!Number.isFinite(cpuElapsedMs) || cpuElapsedMs <= 0) {
  throw new Error(`Invalid CPU elapsed time: ${output.cpu_elapsed_ms}`)
}

if (!(gpuElapsedMs < cpuElapsedMs)) {
  throw new Error(`Expected GPU to be faster than CPU, but gpu=${gpuElapsedMs}ms cpu=${cpuElapsedMs}ms`)
}

output.gpu_vs_cpu_summary = `gpu=${gpuElapsedMs}ms cpu=${cpuElapsedMs}ms`
