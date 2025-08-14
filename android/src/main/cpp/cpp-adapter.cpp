#include <jni.h>
#include "nitrosharereceiverOnLoad.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return margelo::nitro::nitrosharereceiver::initialize(vm);
}
