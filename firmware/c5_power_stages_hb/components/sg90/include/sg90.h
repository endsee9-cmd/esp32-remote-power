#pragma once

#include "esp_err.h"

esp_err_t sg90_init(void);
esp_err_t sg90_move_to_idle(void);
esp_err_t sg90_press_once(void);
esp_err_t sg90_reverse_once(void);
esp_err_t sg90_nudge(int degrees);
/* A zero angle selects the legacy timed press. */
esp_err_t sg90_press_and_return(unsigned down_degrees, unsigned return_degrees);
