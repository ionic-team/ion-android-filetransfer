# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### 2026-10-30

- Chore: Update dependencies and build tooling (Gradle 8.14.3, AGP 8.13.0, compileSdk/targetSdk 36, minSdk 24, androidx libraries).

## 1.0.3

### 2026-01-08

- Fix uploading files with params.

## 1.0.2

### 2025-12-22

- Fix processing of HTTP responses by removing unnecessary gzip header.

## 1.0.1

### 2025-08-01

- Fix uploading files with content URIs by properly initializing Cursor.

### 2025-06-26

- Migrate publishing from OSSRH to Central Portal.

## [1.0.0]

### 2025-03-31

- Implement native library with methods `downloadFile` and `uploadFile`.
