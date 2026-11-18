#include <stdio.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_log.h"
#include "sg90.h"

void app_main(void)
{
    const char *tag = "sg90_test";
    esp_err_t err = sg90_init();
    if (err != ESP_OK) {
        ESP_LOGE(tag, "PWM setup failed: %s", esp_err_to_name(err));
        return;
    }

    setvbuf(stdin, NULL, _IONBF, 0);
    ESP_LOGI(tag, "Use UART monitor: i = released position, p = short press then release");
    for (;;) {
        int key = getchar();
        switch (key) {
        case 'i':
            err = sg90_move_to_idle();
            break;
        case 'p':
            err = sg90_press_once();
            break;
        case EOF:
            clearerr(stdin);
            vTaskDelay(pdMS_TO_TICKS(50));
            continue;
        default:
            continue;
        }
        if (err == ESP_OK) {
            ESP_LOGI(tag, "Action %c completed", key);
        } else {
            ESP_LOGE(tag, "Action %c failed: %s", key, esp_err_to_name(err));
        }
    }
}
