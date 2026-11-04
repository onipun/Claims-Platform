package com.chubb.claims.claim.domain;

public record PropertyLoss(String address, String cause, String occupancy) implements LossDetails {}
