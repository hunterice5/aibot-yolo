cmake_minimum_required(VERSION 3.22.1)

project("aibot_uinput")

# We will build an executable daemon that Shizuku will run
add_executable(uinput_daemon uinput_daemon.cpp)

# Link against Android standard logging and other necessary libraries
target_link_libraries(uinput_daemon log)
