#include <stdio.h>
#include <stdlib.h>

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include "campus_wifi.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "power_mqtt.h"
#include "sg90.h"

static const char *TAG = "remote_power";
static SemaphoreHandle_t servo_lock;

static esp_err_t remote_press(unsigned down_degrees, unsigned return_degrees)
{
    if (xSemaphoreTake(servo_lock, 0) != pdPASS) {
        return ESP_ERR_INVALID_STATE;
    }
    esp_err_t err = sg90_press_and_return(down_degrees, return_degrees);
    xSemaphoreGive(servo_lock);
    return err;
}

static void servo_console(void *arg)
{
    char angle[16];
    size_t length = 0;
    bool reading_angle = false;
    setvbuf(stdin, NULL, _IONBF, 0);
    ESP_LOGI(TAG, "UART: a <signed degrees> + Enter; p = press; r = return");

    for (;;) {
        int key = getchar();
        if (key == EOF) {
            clearerr(stdin);
            vTaskDelay(pdMS_TO_TICKS(50));
            continue;
        }

        int degrees = 0;
        if (reading_angle) {
            if (key != '\r' && key != '\n') {
                if (length < sizeof(angle)) {
                    angle[length++] = (char)key;
                }
                continue;
            }
            reading_angle = false;
            if (length == sizeof(angle)) {
                ESP_LOGW(TAG, "Use a <signed degrees>, from -60 to 60, excluding zero");
                continue;
            }
            angle[length] = '\0';
            char *end;
            long value = strtol(angle, &end, 10);
            while (*end == ' ' || *end == '\t') {
                end++;
            }
            if (*end != '\0' || value == 0 || value < -60 || value > 60) {
                ESP_LOGW(TAG, "Use a <signed degrees>, from -60 to 60, excluding zero");
                continue;
            }
            degrees = (int)value;
            key = 'a';
        } else if (key == 'a') {
            reading_angle = true;
            length = 0;
            continue;
        } else if (key != 'p' && key != 'r' && key != 'i') {
            continue;
        }

        if (xSemaphoreTake(servo_lock, 0) != pdPASS) {
            ESP_LOGW(TAG, "Servo busy; try again after the current action");
            continue;
        }
        esp_err_t err;
        switch (key) {
        case 'a': err = sg90_nudge(degrees); break;
        case 'p': err = sg90_press_once(); break;
        case 'r': err = sg90_reverse_once(); break;
        default: err = sg90_move_to_idle(); break;
        }
        xSemaphoreGive(servo_lock);
        ESP_LOGI(TAG, "UART %c: %s", key, esp_err_to_name(err));
    }
}

void app_main(void)
{
    esp_err_t err = sg90_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "PWM setup failed: %s", esp_err_to_name(err));
        return;
    }
    servo_lock = xSemaphoreCreateMutex();
    ESP_ERROR_CHECK(servo_lock ? ESP_OK : ESP_ERR_NO_MEM);
    ESP_ERROR_CHECK(xTaskCreate(servo_console, "servo_console", 4096, NULL, 5, NULL) == pdPASS
                        ? ESP_OK : ESP_ERR_NO_MEM);
    ESP_ERROR_CHECK(nvs_flash_init());
    if (!campus_wifi_start()) {
        return;
    }
    err = power_link_probe_start();
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Link probe start failed: %s", esp_err_to_name(err));
    }
    err = power_mqtt_start(remote_press);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "MQTT start failed: %s", esp_err_to_name(err));
    }
}
