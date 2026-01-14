#!/bin/bash

# ============================================
# Kubernetes Cleanup Script for Smart API Rate Limiter
# ============================================
# This script removes all Smart API Rate Limiter resources from Kubernetes
# Usage: ./cleanup.sh [options]
#   Options:
#     --keep-namespace: Delete resources but keep the namespace
#     --force: Skip confirmation prompt
# ============================================

set -e  # Exit on error
set -u  # Exit on undefined variable
set -o pipefail  # Exit on pipe failure

# ============================================
# Configuration
# ============================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE="rate-limiter"
KEEP_NAMESPACE=false
FORCE=false

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ============================================
# Parse Command Line Arguments
# ============================================
while [[ $# -gt 0 ]]; do
    case $1 in
        --keep-namespace)
            KEEP_NAMESPACE=true
            shift
            ;;
        --force)
            FORCE=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--keep-namespace] [--force]"
            exit 1
            ;;
    esac
done

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

confirm_cleanup() {
    if [ "${FORCE}" = true ]; then
        return 0
    fi

    log_warning "This will delete all Smart API Rate Limiter resources in namespace '${NAMESPACE}'"
    echo ""

    # Show what will be deleted
    if kubectl get namespace "${NAMESPACE}" &> /dev/null; then
        log_info "Current resources in namespace '${NAMESPACE}':"
        echo ""
        kubectl get all,configmap,pvc,hpa -n "${NAMESPACE}"
        echo ""
    fi

    read -p "Are you sure you want to proceed? (yes/no): " -r
    echo ""

    if [[ ! $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
        log_info "Cleanup cancelled"
        exit 0
    fi
}

delete_resources() {
    log_info "Deleting resources..."

    # Check if namespace exists
    if ! kubectl get namespace "${NAMESPACE}" &> /dev/null; then
        log_warning "Namespace '${NAMESPACE}' does not exist. Nothing to clean up."
        exit 0
    fi

    # Delete HPA first
    log_info "Deleting Horizontal Pod Autoscaler..."
    kubectl delete -f "${SCRIPT_DIR}/hpa.yaml" --ignore-not-found=true
    log_success "HPA deleted"

    # Delete application resources
    log_info "Deleting application resources..."
    kubectl delete -f "${SCRIPT_DIR}/app-service.yaml" --ignore-not-found=true
    kubectl delete -f "${SCRIPT_DIR}/app-deployment.yaml" --ignore-not-found=true
    kubectl delete -f "${SCRIPT_DIR}/app-configmap.yaml" --ignore-not-found=true
    log_success "Application resources deleted"

    # Wait for application pods to terminate
    log_info "Waiting for application pods to terminate..."
    kubectl wait --for=delete pod -l app=smart-rate-limiter -n "${NAMESPACE}" --timeout=60s 2>/dev/null || true

    # Delete Redis resources
    log_info "Deleting Redis resources..."
    kubectl delete -f "${SCRIPT_DIR}/redis-service.yaml" --ignore-not-found=true
    kubectl delete -f "${SCRIPT_DIR}/redis-deployment.yaml" --ignore-not-found=true
    log_success "Redis resources deleted"

    # Wait for Redis pods to terminate
    log_info "Waiting for Redis pods to terminate..."
    kubectl wait --for=delete pod -l app=redis -n "${NAMESPACE}" --timeout=60s 2>/dev/null || true

    # Delete PVCs (if any remain)
    log_info "Deleting Persistent Volume Claims..."
    kubectl delete pvc --all -n "${NAMESPACE}" --ignore-not-found=true
    log_success "PVCs deleted"

    echo ""
}

delete_namespace() {
    if [ "${KEEP_NAMESPACE}" = false ]; then
        log_info "Deleting namespace '${NAMESPACE}'..."
        kubectl delete -f "${SCRIPT_DIR}/namespace.yaml" --ignore-not-found=true

        # Wait for namespace to be fully deleted
        log_info "Waiting for namespace to be deleted..."
        timeout 60s bash -c "while kubectl get namespace ${NAMESPACE} &> /dev/null; do sleep 2; done" || {
            log_warning "Namespace deletion is taking longer than expected"
            log_info "You can check the status with: kubectl get namespace ${NAMESPACE}"
        }

        log_success "Namespace deleted"
    else
        log_info "Keeping namespace '${NAMESPACE}' as requested"
    fi
}

cleanup_port_forwards() {
    log_info "Cleaning up any port-forward processes..."

    # Kill any port-forward processes for this service
    pkill -f "port-forward.*rate-limiter-service" 2>/dev/null || true

    log_success "Port-forward cleanup completed"
}

verify_cleanup() {
    log_info "Verifying cleanup..."

    if kubectl get namespace "${NAMESPACE}" &> /dev/null; then
        REMAINING_RESOURCES=$(kubectl get all,configmap,pvc,hpa -n "${NAMESPACE}" --no-headers 2>/dev/null | wc -l)

        if [ "${REMAINING_RESOURCES}" -gt 0 ]; then
            log_warning "Some resources still remain in namespace '${NAMESPACE}':"
            kubectl get all,configmap,pvc,hpa -n "${NAMESPACE}"
            echo ""
            log_info "You may need to manually delete these resources"
        else
            if [ "${KEEP_NAMESPACE}" = true ]; then
                log_success "All resources deleted. Namespace '${NAMESPACE}' is empty and preserved."
            fi
        fi
    else
        log_success "Namespace '${NAMESPACE}' has been completely removed"
    fi
}

show_cleanup_summary() {
    echo ""
    log_info "Cleanup Summary:"
    echo ""

    log_success "Smart API Rate Limiter has been removed from Kubernetes"
    echo ""

    log_info "Next steps:"
    log_info "  - To redeploy: ./deploy.sh"
    log_info "  - To check cluster status: kubectl get all --all-namespaces"

    if [ "${KEEP_NAMESPACE}" = true ]; then
        log_info "  - Namespace '${NAMESPACE}' was preserved and can be reused"
    fi

    echo ""
}

# ============================================
# Main Execution
# ============================================

main() {
    log_info "Starting cleanup of Smart API Rate Limiter from Kubernetes"
    echo ""

    confirm_cleanup

    cleanup_port_forwards

    delete_resources

    delete_namespace

    verify_cleanup

    show_cleanup_summary

    log_success "Cleanup completed successfully!"
}

# Run main function
main
