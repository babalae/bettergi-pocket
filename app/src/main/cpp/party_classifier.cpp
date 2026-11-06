#include <jni.h>
#include <android/asset_manager_jni.h>
#include <net.h>
#include <memory>
#include <vector>

extern "C" JNIEXPORT jlong JNICALL
Java_com_bettergi_pocket_pathing_party_NcnnAvatarClassifier_nativeCreate(JNIEnv* env, jobject, jobject assets) {
    auto net = std::make_unique<ncnn::Net>();
    net->opt.use_vulkan_compute = false;
    // CMake 关闭 OpenMP；头像输入仅 64×64，使用单线程 CPU 推理。
    net->opt.num_threads = 1;
    // 保持分类置信度稳定，模型本身的权重仍使用提供的 FP16 数据。
    net->opt.use_fp16_arithmetic = false;
    net->opt.use_fp16_storage = false;
    net->opt.use_packing_layout = false;
    auto* manager = AAssetManager_fromJava(env, assets);
    if (!manager || net->load_param(manager, "recognition/Party/avatar_side_classify_sim.param") != 0 ||
        net->load_model(manager, "recognition/Party/avatar_side_classify_sim.bin") != 0) return 0;
    return reinterpret_cast<jlong>(net.release());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_bettergi_pocket_pathing_party_NcnnAvatarClassifier_nativeClassify(JNIEnv* env, jobject, jlong handle, jbyteArray pixels, jint width, jint height) {
    if (!handle || !pixels || width <= 0 || height <= 0 || width > 4096 || height > 4096 ||
        env->GetArrayLength(pixels) != width * height * 3) return nullptr;
    std::vector<unsigned char> bytes(width * height * 3);
    env->GetByteArrayRegion(pixels, 0, bytes.size(), reinterpret_cast<jbyte*>(bytes.data()));
    // 与 PC YOLO 分类一致：RGB、64×64、归一化到 [0, 1]。out0 已有 Softmax。
    ncnn::Mat input = ncnn::Mat::from_pixels_resize(bytes.data(), ncnn::Mat::PIXEL_BGR2RGB, width, height, 64, 64);
    if (input.empty()) return nullptr;
    const float norm[] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
    input.substract_mean_normalize(nullptr, norm);
    auto extractor = reinterpret_cast<ncnn::Net*>(handle)->create_extractor();
    ncnn::Mat output;
    // Mat::total() 包括通道对齐填充，162 类输出的存储长度可能为 164。
    if (extractor.input("in0", input) != 0 || extractor.extract("out0", output) != 0 ||
        output.w * output.h * output.c * output.elempack != 162 || output.elembits() != 32) return nullptr;
    auto result = env->NewFloatArray(162);
    if (result) env->SetFloatArrayRegion(result, 0, 162, static_cast<const float*>(output));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bettergi_pocket_pathing_party_NcnnAvatarClassifier_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<ncnn::Net*>(handle);
}
