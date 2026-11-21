cmake_minimum_required(VERSION 3.22.1)
project(agentpad_pty)

add_library(agentpad_pty SHARED agentpad_pty.c)

find_library(log-lib log)
target_link_libraries(agentpad_pty ${log-lib})
target_compile_options(agentpad_pty PRIVATE -Os -ffunction-sections -fdata-sections)
target_link_options(agentpad_pty PRIVATE -Wl,--gc-sections)
