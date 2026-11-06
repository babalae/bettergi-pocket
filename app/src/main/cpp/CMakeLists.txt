cmake_minimum_required(VERSION 3.22.1)
project(pocket_ncnn LANGUAGES CXX)
include(FetchContent)
# 官方 CPU 源码，固定版本和校验值；首次构建下载，之后使用 CMake 缓存。
# Android 运行时可见的 CPU 数量/拓扑可能不一致，LLVM OpenMP 会断言并 abort。
# 权重加载时的 FP16 -> FP32 转换也会进入 OpenMP，仅设置 opt.num_threads=1 不够。
# 从源码关闭 OpenMP，保留原始模型和各 ABI 的 CPU/SIMD 实现。
set(NCNN_VERSION 20260526 CACHE STRING "Pinned ncnn version" FORCE)
set(NCNN_OPENMP OFF CACHE BOOL "Avoid LLVM OpenMP CPU topology initialization" FORCE)
set(NCNN_SIMPLEOMP OFF CACHE BOOL "No OpenMP runtime" FORCE)
set(NCNN_VULKAN OFF CACHE BOOL "CPU inference only" FORCE)
set(NCNN_SHARED_LIB OFF CACHE BOOL "Link ncnn into JNI" FORCE)
set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "No SDK benchmarks" FORCE)
set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "No SDK examples" FORCE)
set(NCNN_BUILD_TOOLS OFF CACHE BOOL "No model conversion tools" FORCE)
set(NCNN_BUILD_TESTS OFF CACHE BOOL "No SDK tests" FORCE)
set(NCNN_INSTALL_SDK OFF CACHE BOOL "No SDK installation" FORCE)
FetchContent_Declare(ncnn_sdk
    URL https://codeload.github.com/Tencent/ncnn/tar.gz/refs/tags/20260526
    URL_HASH SHA256=da1ade826bc22858a9fb87ae052789bbd614d042b3ec2c22e6544ca83db6bc04)
FetchContent_MakeAvailable(ncnn_sdk)
add_library(pocket_ncnn SHARED party_classifier.cpp)
target_compile_features(pocket_ncnn PRIVATE cxx_std_17)
target_link_libraries(pocket_ncnn ncnn android log)
# ncnn 是 JNI 的私有依赖，避免静态库符号与其他原生库互相干扰。
target_link_options(pocket_ncnn PRIVATE "-Wl,--exclude-libs,ALL")
# Android 15+ 的 16 KiB page size。
target_link_options(pocket_ncnn PRIVATE "-Wl,-z,max-page-size=16384")
