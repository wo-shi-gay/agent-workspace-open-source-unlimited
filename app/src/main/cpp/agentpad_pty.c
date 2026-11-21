#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <termios.h>
#include <unistd.h>

JNIEXPORT jint JNICALL Java_com_agentpad_newapp_ai_PtyBridge_open(JNIEnv *env, jclass cls, jint rows, jint cols) {
    (void)env; (void)cls;
    int master = -1;
#if defined(__ANDROID__)
    master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) return -errno;
    if (grantpt(master) != 0 || unlockpt(master) != 0) { int e = errno; close(master); return -e; }
    struct winsize ws = { (unsigned short)rows, (unsigned short)cols, 0, 0 };
    ioctl(master, TIOCSWINSZ, &ws);
#endif
    return master;
}

JNIEXPORT jint JNICALL Java_com_agentpad_newapp_ai_PtyBridge_read(JNIEnv *env, jclass cls, jint fd, jbyteArray out) {
    (void)cls;
    jsize size = (*env)->GetArrayLength(env, out);
    jbyte *buf = (*env)->GetByteArrayElements(env, out, NULL);
    ssize_t n = read(fd, buf, (size_t)size);
    (*env)->ReleaseByteArrayElements(env, out, buf, n > 0 ? 0 : JNI_ABORT);
    return n >= 0 ? (jint)n : -errno;
}

JNIEXPORT jint JNICALL Java_com_agentpad_newapp_ai_PtyBridge_write(JNIEnv *env, jclass cls, jint fd, jbyteArray data) {
    (void)cls;
    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    ssize_t n = write(fd, buf, (size_t)size);
    (*env)->ReleaseByteArrayElements(env, data, JNI_ABORT);
    return n >= 0 ? (jint)n : -errno;
}

JNIEXPORT jint JNICALL Java_com_agentpad_newapp_ai_PtyBridge_resize(JNIEnv *env, jclass cls, jint fd, jint rows, jint cols) {
    (void)env; (void)cls;
    struct winsize ws = { (unsigned short)rows, (unsigned short)cols, 0, 0 };
    return ioctl(fd, TIOCSWINSZ, &ws) == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL Java_com_agentpad_newapp_ai_PtyBridge_interrupt(JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    unsigned char c = 3;
    return write(fd, &c, 1) == 1 ? 0 : -errno;
}

JNIEXPORT void JNICALL Java_com_agentpad_newapp_ai_PtyBridge_close(JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    close(fd);
}