#pragma once

#include <stdbool.h>

/* Start Wi-Fi and wait for the first IPv4 address. Return false in scan-only mode. */
bool campus_wifi_start(void);
