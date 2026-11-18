#pragma once

#include "esp_err.h"

/* Connect after Wi-Fi has obtained an IP. Run each accepted command in a worker task. */
esp_err_t power_mqtt_start(esp_err_t (*action)(unsigned down_degrees, unsigned return_degrees));
esp_err_t power_link_probe_start(void);
