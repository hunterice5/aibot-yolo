#include <iostream>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <linux/uinput.h>
#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>

#define LOG_TAG "AimbotUinput"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

const int SCREEN_WIDTH = 3000; // จะปรับให้รับค่าจากแอพทีหลัง
const int SCREEN_HEIGHT = 3000;

int create_virtual_touchscreen() {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("Failed to open /dev/uinput. Are you root or Shizuku?");
        return -1;
    }

    // Enable touch events
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);

    // Enable multi-touch properties
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "Aimbot_Virtual_Touch");
    uidev.id.bustype = BUS_VIRTUAL;
    uidev.id.vendor  = 0x1234;
    uidev.id.product = 0x5678;
    uidev.id.version = 1;

    uidev.absmin[ABS_MT_SLOT] = 0;
    uidev.absmax[ABS_MT_SLOT] = 9; // Support 10 slots (we will use slot 8 or 9)
    uidev.absmin[ABS_MT_POSITION_X] = 0;
    uidev.absmax[ABS_MT_POSITION_X] = SCREEN_WIDTH;
    uidev.absmin[ABS_MT_POSITION_Y] = 0;
    uidev.absmax[ABS_MT_POSITION_Y] = SCREEN_HEIGHT;
    uidev.absmin[ABS_MT_TRACKING_ID] = 0;
    uidev.absmax[ABS_MT_TRACKING_ID] = 65535;

    if (write(fd, &uidev, sizeof(uidev)) < 0) {
        LOGE("Failed to write to uinput device.");
        close(fd);
        return -1;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("Failed to create uinput device.");
        close(fd);
        return -1;
    }

    LOGI("Virtual Touchscreen created successfully!");
    return fd;
}

// Helper to inject events
void emit(int fd, int type, int code, int val) {
    struct input_event ie;
    memset(&ie, 0, sizeof(ie));
    ie.type = type;
    ie.code = code;
    ie.value = val;
    write(fd, &ie, sizeof(ie));
}

void inject_aim_touch(int fd, int x, int y) {
    // ใช้ Slot 9 เพื่อไม่ให้ตีกับนิ้วคนเล่น (ที่มักจะใช้ Slot 0,1)
    emit(fd, EV_ABS, ABS_MT_SLOT, 9);
    emit(fd, EV_ABS, ABS_MT_TRACKING_ID, 999);
    emit(fd, EV_ABS, ABS_MT_POSITION_X, x);
    emit(fd, EV_ABS, ABS_MT_POSITION_Y, y);
    emit(fd, EV_KEY, BTN_TOUCH, 1);
    emit(fd, EV_SYN, SYN_REPORT, 0);
    
    usleep(20000); // กดค้างไว้ 20ms
    
    emit(fd, EV_ABS, ABS_MT_SLOT, 9);
    emit(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    emit(fd, EV_KEY, BTN_TOUCH, 0);
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

int main(int argc, char *argv[]) {
    LOGI("Daemon Starting...");
    
    int uinput_fd = create_virtual_touchscreen();
    if (uinput_fd < 0) {
        return 1;
    }

    LOGI("Daemon is ready. Listening for commands...");
    
    // ตรงนี้ในอนาคตเราจะรับค่า x, y มาจากตัวแอพ Android ผ่าน Local Socket
    // แต่เพื่อการทดสอบ ลองจำลองการทัชไปที่พิกัด 1000, 1000
    inject_aim_touch(uinput_fd, 1000, 1000);
    LOGI("Test touch injected!");

    // ถ่วงเวลาไว้ไม่ให้โปรแกรมปิดทันที เพื่อให้จอจำลองยังคงอยู่
    while(true) {
        sleep(10); 
    }

    ioctl(uinput_fd, UI_DEV_DESTROY);
    close(uinput_fd);
    return 0;
}
