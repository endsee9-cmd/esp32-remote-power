#include "campus_wifi.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "power_mqtt.h"

static esp_err_t log_press(unsigned down_degrees, unsigned return_degrees)
{
    ESP_LOGI("mqtt_test", "Press command accepted: down=%u return=%u", down_degrees, return_degrees);
    return ESP_OK;
}

void app_main(void)
{
    ESP_ERROR_CHECK(nvs_flash_init());
    if (!campus_wifi_start()) {
        return;
    }
    esp_err_t err = power_mqtt_start(log_press);
    if (err != ESP_OK) {
        ESP_LOGE("mqtt_test", "MQTT start failed: %s", esp_err_to_name(err));
    }
}
