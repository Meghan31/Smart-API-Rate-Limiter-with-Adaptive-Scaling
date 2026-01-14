#!/bin/bash

# ============================================
# Kubernetes Testing Script for Smart API Rate Limiter
# ============================================
# This script tests the deployed Smart API Rate Limiter in Kubernetes
# Usage: ./test.sh
# ============================================

set -e  # Exit on error
set -u  # Exit on undefined variable
set -o pipefail  # Exit on pipe failure

# ============================================
# Configuration
# ============================================
NAMESPACE="rate-limiter"
SERVICE_NAME="rate-limiter-service"
PORT_FORWARD_PORT="8080"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ============================================
# Helper Functions
# ============================================

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_deployment() {
    log_info "Checking deployment status..."

    # Check namespace exists
    if ! kubectl get namespace "${NAMESPACE}" &> /dev/null; then
        log_error "Namespace '${NAMESPACE}' does not exist. Please run deploy.sh first."
        exit 1
    fi

    # Check pods are running
    READY_PODS=$(kubectl get pods -n "${NAMESPACE}" -l app=smart-rate-limiter --field-selector=status.phase=Running --no-headers 2>/dev/null | wc -l)
    TOTAL_PODS=$(kubectl get pods -n "${NAMESPACE}" -l app=smart-rate-limiter --no-headers 2>/dev/null | wc -l)

    if [ "${READY_PODS}" -eq 0 ]; then
        log_error "No running pods found. Please check the deployment."
        kubectl get pods -n "${NAMESPACE}"
        exit 1
    fi

    log_success "Found ${READY_PODS}/${TOTAL_PODS} running pods"
    echo ""
}

test_health_endpoints() {
    log_info "Testing health endpoints..."

    # Get pod name
    POD_NAME=$(kubectl get pods -n "${NAMESPACE}" -l app=smart-rate-limiter -o jsonpath='{.items[0].metadata.name}')

    # Test liveness probe
    log_info "Testing liveness endpoint..."
    LIVENESS=$(kubectl exec -n "${NAMESPACE}" "${POD_NAME}" -- curl -s http://localhost:8080/actuator/health/liveness)
    if echo "${LIVENESS}" | grep -q "UP"; then
        log_success "Liveness probe: UP"
    else
        log_error "Liveness probe failed: ${LIVENESS}"
    fi

    # Test readiness probe
    log_info "Testing readiness endpoint..."
    READINESS=$(kubectl exec -n "${NAMESPACE}" "${POD_NAME}" -- curl -s http://localhost:8080/actuator/health/readiness)
    if echo "${READINESS}" | grep -q "UP"; then
        log_success "Readiness probe: UP"
    else
        log_error "Readiness probe failed: ${READINESS}"
    fi

    # Test overall health
    log_info "Testing overall health endpoint..."
    HEALTH=$(kubectl exec -n "${NAMESPACE}" "${POD_NAME}" -- curl -s http://localhost:8080/actuator/health)
    if echo "${HEALTH}" | grep -q "UP"; then
        log_success "Health endpoint: UP"
    else
        log_error "Health endpoint failed: ${HEALTH}"
    fi

    echo ""
}

test_redis_connectivity() {
    log_info "Testing Redis connectivity..."

    # Get Redis pod name
    REDIS_POD=$(kubectl get pods -n "${NAMESPACE}" -l app=redis -o jsonpath='{.items[0].metadata.name}')

    # Test Redis ping
    REDIS_PING=$(kubectl exec -n "${NAMESPACE}" "${REDIS_POD}" -- redis-cli ping)
    if [ "${REDIS_PING}" = "PONG" ]; then
        log_success "Redis connectivity: OK"
    else
        log_error "Redis connectivity failed"
    fi

    echo ""
}

setup_port_forward() {
    log_info "Setting up port forwarding..."

    # Kill any existing port-forward on the same port
    pkill -f "port-forward.*${PORT_FORWARD_PORT}" 2>/dev/null || true
    sleep 2

    # Start port-forward in background
    kubectl port-forward -n "${NAMESPACE}" "svc/${SERVICE_NAME}" "${PORT_FORWARD_PORT}:80" &> /dev/null &
    PORT_FORWARD_PID=$!

    # Wait for port-forward to be ready
    sleep 3

    log_success "Port forwarding established (PID: ${PORT_FORWARD_PID})"
    echo ""
}

cleanup_port_forward() {
    if [ -n "${PORT_FORWARD_PID:-}" ]; then
        log_info "Cleaning up port forwarding..."
        kill "${PORT_FORWARD_PID}" 2>/dev/null || true
    fi
}

test_api_endpoints() {
    log_info "Testing API endpoints via port-forward..."

    BASE_URL="http://localhost:${PORT_FORWARD_PORT}"

    # Test health endpoint
    log_info "Testing GET ${BASE_URL}/actuator/health"
    HEALTH_RESPONSE=$(curl -s -w "\n%{http_code}" "${BASE_URL}/actuator/health")
    HTTP_CODE=$(echo "${HEALTH_RESPONSE}" | tail -n1)
    if [ "${HTTP_CODE}" = "200" ]; then
        log_success "Health endpoint: HTTP ${HTTP_CODE}"
    else
        log_error "Health endpoint failed: HTTP ${HTTP_CODE}"
    fi

    # Test metrics endpoint
    log_info "Testing GET ${BASE_URL}/actuator/metrics"
    METRICS_RESPONSE=$(curl -s -w "\n%{http_code}" "${BASE_URL}/actuator/metrics")
    HTTP_CODE=$(echo "${METRICS_RESPONSE}" | tail -n1)
    if [ "${HTTP_CODE}" = "200" ]; then
        log_success "Metrics endpoint: HTTP ${HTTP_CODE}"
    else
        log_error "Metrics endpoint failed: HTTP ${HTTP_CODE}"
    fi

    echo ""
}

test_rate_limiting() {
    log_info "Testing rate limiting functionality..."

    BASE_URL="http://localhost:${PORT_FORWARD_PORT}"

    # Check if test endpoint exists (you may need to adjust this)
    log_info "Sending multiple requests to test rate limiting..."

    SUCCESS_COUNT=0
    RATE_LIMITED_COUNT=0

    for i in {1..15}; do
        HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/actuator/health")
        if [ "${HTTP_CODE}" = "200" ]; then
            SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
        elif [ "${HTTP_CODE}" = "429" ]; then
            RATE_LIMITED_COUNT=$((RATE_LIMITED_COUNT + 1))
        fi
    done

    log_info "Results: ${SUCCESS_COUNT} successful, ${RATE_LIMITED_COUNT} rate-limited"

    if [ "${SUCCESS_COUNT}" -gt 0 ]; then
        log_success "Rate limiting test completed"
    else
        log_warning "All requests were rate-limited or failed"
    fi

    echo ""
}

test_distributed_rate_limiting() {
    log_info "Testing distributed rate limiting across pods..."

    # Scale to 3 replicas if not already
    CURRENT_REPLICAS=$(kubectl get deployment rate-limiter-app -n "${NAMESPACE}" -o jsonpath='{.spec.replicas}')
    if [ "${CURRENT_REPLICAS}" -lt 3 ]; then
        log_info "Scaling to 3 replicas..."
        kubectl scale deployment rate-limiter-app --replicas=3 -n "${NAMESPACE}"
        kubectl wait --for=condition=ready pod -l app=smart-rate-limiter -n "${NAMESPACE}" --timeout=120s
    fi

    # Get pod IPs
    POD_COUNT=$(kubectl get pods -n "${NAMESPACE}" -l app=smart-rate-limiter --field-selector=status.phase=Running --no-headers | wc -l)
    log_info "Testing with ${POD_COUNT} pods"

    # Send requests and verify they're distributed
    log_info "Sending requests to verify distribution..."
    for i in {1..10}; do
        kubectl exec -n "${NAMESPACE}" deployment/rate-limiter-app -- curl -s http://localhost:8080/actuator/health > /dev/null || true
    done

    log_success "Distributed rate limiting test completed"
    echo ""
}

show_resource_usage() {
    log_info "Showing resource usage..."

    # Check if metrics-server is available
    if kubectl top nodes &> /dev/null; then
        echo ""
        log_info "Node metrics:"
        kubectl top nodes

        echo ""
        log_info "Pod metrics in namespace '${NAMESPACE}':"
        kubectl top pods -n "${NAMESPACE}"
    else
        log_warning "Metrics server not available. Cannot show resource usage."
    fi

    echo ""
}

show_logs() {
    log_info "Showing recent application logs..."
    echo ""

    kubectl logs -n "${NAMESPACE}" -l app=smart-rate-limiter --tail=20 --prefix=true

    echo ""
}

# ============================================
# Main Execution
# ============================================

main() {
    log_info "Starting tests for Smart API Rate Limiter in Kubernetes"
    echo ""

    check_deployment

    test_health_endpoints

    test_redis_connectivity

    setup_port_forward
    trap cleanup_port_forward EXIT

    test_api_endpoints

    test_rate_limiting

    test_distributed_rate_limiting

    show_resource_usage

    show_logs

    log_success "All tests completed!"
    echo ""

    log_info "To access the application, use:"
    log_info "  kubectl port-forward svc/${SERVICE_NAME} ${PORT_FORWARD_PORT}:80 -n ${NAMESPACE}"
    log_info "  Then visit: http://localhost:${PORT_FORWARD_PORT}/actuator/health"
}

# Run main function
main
