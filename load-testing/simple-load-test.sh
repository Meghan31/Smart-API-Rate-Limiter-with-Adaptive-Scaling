#!/bin/bash

# Smart API Rate Limiter - Load Testing Script
# Usage: ./simple-load-test.sh [normal|spike|sustained|multi-user]

set -e

# Configuration
BASE_URL="${BASE_URL:-http://localhost:8080}"
RESULTS_DIR="./results"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test API keys
API_KEY_USER1="test-key-user1"
API_KEY_USER2="test-key-user2"
API_KEY_USER3="test-key-user3"

# Create results directory
mkdir -p "$RESULTS_DIR"

# Function to print colored output
print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

# Function to make a single request
make_request() {
    local api_key=$1
    local endpoint=${2:-/api/test}

    response=$(curl -s -w "\n%{http_code}" -H "X-API-Key: $api_key" "$BASE_URL$endpoint" 2>/dev/null)
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    echo "$http_code"
}

# Function to send concurrent requests
send_concurrent_requests() {
    local num_requests=$1
    local api_key=$2
    local concurrent=$3
    local description=$4

    print_header "Test: $description"
    echo "Requests: $num_requests | Concurrency: $concurrent | API Key: $api_key"
    echo ""

    local result_file="$RESULTS_DIR/test_${TIMESTAMP}_$(echo "$description" | tr ' ' '_' | tr '[:upper:]' '[:lower:]').txt"

    local start_time=$(date +%s)
    local success_count=0
    local rate_limited_count=0
    local error_count=0
    local total_response_time=0

    # Send requests in batches for concurrency
    for ((i=0; i<num_requests; i+=concurrent)); do
        local batch_size=$concurrent
        if [ $((i + concurrent)) -gt $num_requests ]; then
            batch_size=$((num_requests - i))
        fi

        # Send concurrent requests
        for ((j=0; j<batch_size; j++)); do
            (
                req_start=$(date +%s%N)
                http_code=$(make_request "$api_key")
                req_end=$(date +%s%N)
                req_time=$(( (req_end - req_start) / 1000000 ))

                echo "$http_code|$req_time" >> "$result_file.tmp"
            ) &
        done

        wait

        # Progress indicator
        progress=$((i + batch_size))
        percentage=$((progress * 100 / num_requests))
        echo -ne "Progress: $progress/$num_requests ($percentage%) \r"
    done

    echo "" # New line after progress

    local end_time=$(date +%s)
    local duration=$((end_time - start_time))

    # Process results
    if [ -f "$result_file.tmp" ]; then
        while IFS='|' read -r code time; do
            total_response_time=$((total_response_time + time))
            case $code in
                200)
                    success_count=$((success_count + 1))
                    ;;
                429)
                    rate_limited_count=$((rate_limited_count + 1))
                    ;;
                *)
                    error_count=$((error_count + 1))
                    ;;
            esac
        done < "$result_file.tmp"
        rm "$result_file.tmp"
    fi

    # Calculate metrics
    local avg_response_time=$((total_response_time / num_requests))
    local throughput=$((num_requests / duration))
    local success_rate=$((success_count * 100 / num_requests))

    # Generate report
    {
        echo "=========================================="
        echo "Load Test Report"
        echo "=========================================="
        echo "Test: $description"
        echo "Timestamp: $(date)"
        echo "Duration: ${duration}s"
        echo ""
        echo "Configuration:"
        echo "  Total Requests: $num_requests"
        echo "  Concurrency: $concurrent"
        echo "  API Key: $api_key"
        echo ""
        echo "Results:"
        echo "  Successful (200): $success_count"
        echo "  Rate Limited (429): $rate_limited_count"
        echo "  Errors: $error_count"
        echo ""
        echo "Performance Metrics:"
        echo "  Total Duration: ${duration}s"
        echo "  Throughput: ${throughput} req/s"
        echo "  Success Rate: ${success_rate}%"
        echo "  Avg Response Time: ${avg_response_time}ms"
        echo ""
        echo "Rate Limiting Effectiveness:"
        if [ $rate_limited_count -gt 0 ]; then
            echo "  ✓ Rate limiting is working (${rate_limited_count} requests limited)"
        else
            echo "  ⚠ No rate limiting observed"
        fi
        echo "=========================================="
    } | tee "$result_file"

    # Print summary to console
    echo ""
    print_success "Test completed in ${duration}s"
    echo "  Throughput: ${throughput} req/s"
    echo "  Success Rate: ${success_rate}%"
    echo "  Rate Limited: ${rate_limited_count}"
    echo "  Avg Response Time: ${avg_response_time}ms"
    echo ""
    echo "Full report saved to: $result_file"
    echo ""
}

# Test scenario: Normal Load
test_normal_load() {
    print_header "SCENARIO: Normal Load"
    echo "Testing with typical production load"
    echo ""

    # 500 requests with concurrency of 10 (simulates 50 req/s)
    send_concurrent_requests 500 "$API_KEY_USER1" 10 "Normal Load - 500 requests"
}

# Test scenario: Spike Load
test_spike_load() {
    print_header "SCENARIO: Spike Load"
    echo "Testing with sudden traffic spike"
    echo ""

    # 1000 requests with high concurrency (simulates 1000+ req/s)
    send_concurrent_requests 1000 "$API_KEY_USER1" 50 "Spike Load - 1000 concurrent requests"
}

# Test scenario: Sustained Load
test_sustained_load() {
    print_header "SCENARIO: Sustained Load"
    echo "Testing with sustained moderate load over time"
    echo ""

    # Send requests in waves
    for wave in {1..3}; do
        echo ""
        print_warning "Wave $wave of 3"
        send_concurrent_requests 300 "$API_KEY_USER1" 15 "Sustained Load - Wave $wave"

        if [ $wave -lt 3 ]; then
            echo "Waiting 10 seconds before next wave..."
            sleep 10
        fi
    done
}

# Test scenario: Multi-User
test_multi_user() {
    print_header "SCENARIO: Multi-User Load"
    echo "Testing with multiple API keys simultaneously"
    echo ""

    # Run tests for multiple users in parallel
    (send_concurrent_requests 300 "$API_KEY_USER1" 10 "Multi-User - User 1") &
    pid1=$!

    (send_concurrent_requests 300 "$API_KEY_USER2" 10 "Multi-User - User 2") &
    pid2=$!

    (send_concurrent_requests 300 "$API_KEY_USER3" 10 "Multi-User - User 3") &
    pid3=$!

    # Wait for all tests to complete
    wait $pid1 $pid2 $pid3

    print_success "All multi-user tests completed"
}

# Test scenario: Rate Limit Verification
test_rate_limit_verification() {
    print_header "SCENARIO: Rate Limit Verification"
    echo "Testing rate limiting threshold accuracy"
    echo ""

    # Send requests that should definitely trigger rate limiting
    # Assuming rate limit is 100 req/min, send 200 requests rapidly
    send_concurrent_requests 200 "$API_KEY_USER1" 50 "Rate Limit Verification - Rapid requests"
}

# Check if service is running
check_service() {
    print_header "Pre-flight Check"
    echo "Checking if service is running at $BASE_URL"

    if curl -s -f "$BASE_URL/actuator/health" > /dev/null 2>&1; then
        print_success "Service is running"
        echo ""
        return 0
    else
        print_error "Service is not running at $BASE_URL"
        echo "Please start the service before running load tests"
        exit 1
    fi
}

# Main script
main() {
    local scenario=${1:-all}

    print_header "Smart API Rate Limiter - Load Testing"
    echo "Base URL: $BASE_URL"
    echo "Results Directory: $RESULTS_DIR"
    echo "Timestamp: $TIMESTAMP"
    echo ""

    check_service

    case $scenario in
        normal)
            test_normal_load
            ;;
        spike)
            test_spike_load
            ;;
        sustained)
            test_sustained_load
            ;;
        multi-user)
            test_multi_user
            ;;
        verify)
            test_rate_limit_verification
            ;;
        all)
            test_normal_load
            sleep 5
            test_spike_load
            sleep 5
            test_sustained_load
            sleep 5
            test_multi_user
            sleep 5
            test_rate_limit_verification
            ;;
        *)
            echo "Usage: $0 [normal|spike|sustained|multi-user|verify|all]"
            echo ""
            echo "Scenarios:"
            echo "  normal      - Normal production load (500 requests, low concurrency)"
            echo "  spike       - Traffic spike (1000 requests, high concurrency)"
            echo "  sustained   - Sustained load over time (3 waves of 300 requests)"
            echo "  multi-user  - Multiple users simultaneously (3 users, 300 requests each)"
            echo "  verify      - Rate limit verification (200 rapid requests)"
            echo "  all         - Run all scenarios (default)"
            exit 1
            ;;
    esac

    echo ""
    print_header "Load Testing Complete"
    echo "Results saved in: $RESULTS_DIR"
    echo ""
    echo "To view monitoring stats:"
    echo "  curl $BASE_URL/api/monitoring/stats | jq"
    echo ""
}

main "$@"
