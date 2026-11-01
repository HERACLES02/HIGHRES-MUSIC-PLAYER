cmake_minimum_required(VERSION 3.22.1)
project(local_music_usb LANGUAGES C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(vendor/flac flac-build EXCLUDE_FROM_ALL)
set(VENDOR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/libusb)
add_library(usb-1.0 SHARED
    ${VENDOR}/libusb/core.c ${VENDOR}/libusb/descriptor.c
    ${VENDOR}/libusb/hotplug.c ${VENDOR}/libusb/io.c
    ${VENDOR}/libusb/sync.c ${VENDOR}/libusb/strerror.c
    ${VENDOR}/libusb/os/linux_usbfs.c ${VENDOR}/libusb/os/events_posix.c
    ${VENDOR}/libusb/os/threads_posix.c ${VENDOR}/libusb/os/linux_netlink.c)
target_include_directories(usb-1.0 PRIVATE ${VENDOR}/android ${VENDOR}/libusb ${VENDOR}/libusb/os)
target_compile_options(usb-1.0 PRIVATE -fvisibility=hidden -pthread)
target_link_libraries(usb-1.0 PRIVATE log)
add_library(usb_experiment SHARED diagnostic.cpp pcm_decode.cpp)
target_include_directories(usb_experiment PRIVATE ${VENDOR}/libusb)
target_compile_options(usb_experiment PRIVATE -Wall -Wextra -Werror)
target_link_libraries(usb_experiment PRIVATE usb-1.0 FLAC)
