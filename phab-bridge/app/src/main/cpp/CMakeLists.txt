cmake_minimum_required(VERSION 3.22.1)
project(phonebridge_native)

add_library(phonebridge_native SHARED
    phonebridge_native.cpp
)

target_link_libraries(phonebridge_native
    log
    dl
)
