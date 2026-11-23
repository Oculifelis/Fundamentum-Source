package com.fundamentum.climate;

import com.fundamentum.port.FId;

/**
 * {@code fundamentum:season_changed}: the world's season moved on. {@code from} is null on the first season after
 * seasons were turned on, {@code to} is null when they were turned off. {@code day} is the calendar day it happened.
 */
public record SeasonChanged(FId from, FId to, long day) { }
