#!/bin/bash

# =============================================================================
# Start IS with GraalJS Sidecar
# =============================================================================
# This script starts the GraalJS sidecar and then the Identity Server.
# The sidecar must be running before IS starts for gRPC communication to work.
# =============================================================================

set -e

# Configuration
SIDECAR_DIR="/Users/bashitha/Downloads/product/external-graaljs"
SIDECAR_JAR="$SIDECAR_DIR/target/graaljs-sidecar-1.0.0-SNAPSHOT.jar"
SOCKET_PATH="/tmp/graaljs.sock"
STATEMENT_LIMIT="5000"
SIDECAR_LOG="$SIDECAR_DIR/sidecar.log"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Functions
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

cleanup() {
    if [ ! -z "$SIDECAR_PID" ]; then
        print_info "Stopping GraalJS Sidecar (PID: $SIDECAR_PID)..."
        kill $SIDECAR_PID 2>/dev/null || true
    fi
    
    if [ -S "$SOCKET_PATH" ]; then
        print_info "Removing socket file..."
        rm -f "$SOCKET_PATH"
    fi
}

# Trap cleanup on exit
trap cleanup EXIT INT TERM

# Validate sidecar JAR exists
if [ ! -f "$SIDECAR_JAR" ]; then
    print_error "Sidecar JAR not found at: $SIDECAR_JAR"
    print_info "Building sidecar..."
    cd "$SIDECAR_DIR"
    mvn clean package -DskipTests
    if [ $? -ne 0 ]; then
        print_error "Failed to build sidecar"
        exit 1
    fi
fi

# Clean up old socket if exists
if [ -S "$SOCKET_PATH" ]; then
    print_warn "Removing existing socket at $SOCKET_PATH"
    rm -f "$SOCKET_PATH"
fi

# Start GraalJS Sidecar
print_info "Starting GraalJS Sidecar..."
print_info "Socket path: $SOCKET_PATH"
print_info "Statement limit: $STATEMENT_LIMIT"
print_info "Log file: $SIDECAR_LOG"

cd "$SIDECAR_DIR"
java -jar "$SIDECAR_JAR" "$SOCKET_PATH" "$STATEMENT_LIMIT" > "$SIDECAR_LOG" 2>&1 &
SIDECAR_PID=$!

print_info "Sidecar started with PID: $SIDECAR_PID"

# Wait for socket to be created
print_info "Waiting for socket creation..."
SOCKET_WAIT=0
MAX_WAIT=15

while [ $SOCKET_WAIT -lt $MAX_WAIT ]; do
    if [ -S "$SOCKET_PATH" ]; then
        print_info "Socket created successfully at $SOCKET_PATH"
        break
    fi
    
    # Check if sidecar process is still running
    if ! kill -0 $SIDECAR_PID 2>/dev/null; then
        print_error "Sidecar process died unexpectedly"
        print_error "Check logs at: $SIDECAR_LOG"
        tail -n 20 "$SIDECAR_LOG"
        exit 1
    fi
    
    sleep 1
    SOCKET_WAIT=$((SOCKET_WAIT + 1))
done

if [ ! -S "$SOCKET_PATH" ]; then
    print_error "Socket not created after ${MAX_WAIT}s"
    print_error "Check logs at: $SIDECAR_LOG"
    tail -n 20 "$SIDECAR_LOG"
    exit 1
fi

# Verify socket permissions
print_info "Socket permissions: $(ls -la $SOCKET_PATH)"

print_info ""
print_info "=========================================="
print_info "GraalJS Sidecar is ready!"
print_info "=========================================="
print_info ""
print_info "You can now start the Identity Server."
print_info ""
print_info "To start IS manually:"
print_info "  cd <IS_PACK_HOME>/bin"
print_info "  sh wso2server.sh -Dosgi.clean=true"
print_info ""
print_info "To monitor sidecar logs:"
print_info "  tail -f $SIDECAR_LOG"
print_info ""
print_info "Press Ctrl+C to stop the sidecar"
print_info "=========================================="

# Keep script running and show logs
tail -f "$SIDECAR_LOG"
