package com.chubb.claims.claim.domain;

public sealed interface LossDetails permits MotorLoss, PropertyLoss {}
