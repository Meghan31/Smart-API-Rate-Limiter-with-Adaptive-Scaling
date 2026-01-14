#!/bin/bash

# ============================================
# Kubernetes Deployment Script for Smart API Rate Limiter
# ============================================
# This script deploys the Smart API Rate Limiter to Kubernetes
# Usage: ./deploy.sh [environment]
#   environment: dev, staging, or prod (default: dev)
# ============================================

set -e  # Exit on error
set -u  # Exit on undefined variable
set -o pipefail  # Exit on pipe failure

# ============================================
# Configuration
# ============================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
NAMESPACE="rate-limiter"
ENVIRONMENT="${1:-dev}"

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

check_prerequisites() {
    log_info "Checking prerequisites..."

    # Check if kubectl is installed
    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl is not installed. Please install kubectl first."
        exit 1
    fi

    # Check if cluster is accessible
    if ! kubectl cluster-info &> /dev/null; then
        log_error "Cannot connect to Kubernetes cluster. Please check your kubeconfig."
        exit 1
    fi

    # Check if Docker image exists
    if ! docker images | grep -q "smart-rate-limiter"; then
        log_warning "Docker image 'smart-rate-limiter:latest' not found."
        log_info "Building Docker image..."
        cd "${PROJECT_ROOT}"
        docker build -t smart-rate-limiter:latest .
        log_success "Docker image built successfully"
    fi

    log_success "Prerequisites check passed"
}

create_namespace() {
    log_info "Creating namespace '${NAMESPACE}'..."

    if kubectl get namespace "${NAMESPACE}" &> /dev/null; then
        log_warning "Namespace '${NAMESPACE}' already exists"
    else
        kubectl apply -f "${SCRIPT_DIR}/namespace.yaml"
        log_success "Namespace created"
    fi
}

deploy_redis() {
    log_info "Deploying Redis StatefulSet..."

    kubectl apply -f "${SCRIPT_DIR}/redis-deployment.yaml"
    kubectl apply -f "${SCRIPT_DIR}/redis-service.yaml"

    log_info "Waiting for Redis to be ready..."
    kubectl wait --for=condition=ready pod -l app=redis -n "${NAMESPACE}" --timeout=300s || {
        log_error "Redis failed to start within 5 minutes"
        exit 1
    }

    log_success "Redis deployed successfully"
}

deploy_application() {
    log_info "Deploying Smart API Rate Limiter application..."

    # Apply ConfigMap
    kubectl apply -f "${SCRIPT_DIR}/app-configmap.yaml"

    # Apply Deployment
    kubectl apply -f "${SCRIPT_DIR}/app-deployment.yaml"

    # Apply Service
    kubectl apply -f "${SCRIPT_DIR}/app-service.yaml"

    log_info "Waiting for application pods to be ready..."
    kubectl wait --for=condition=ready pod -l app=smart-rate-limiter -n "${NAMESPACE}" --timeout=300s || {
        log_error "Application failed to start within 5 minutes"
        log_info "Checking pod status..."
        kubectl get pods -n "${NAMESPACE}"
        log_info "Checking pod logs..."
        kubectl logs -l app=smart-rate-limiter -n "${NAMESPACE}" --tail=50
        exit 1
    }

    log_success "Application deployed successfully"
}

deploy_hpa() {
    log_info "Deploying Horizontal Pod Autoscaler..."

    # Check if metrics-server is available
    if ! kubectl get deployment metrics-server -n kube-system &> /dev/null; then
        log_warning "Metrics server not found. HPA will not work without it."
        log_info "To enable HPA, install metrics-server:"
        log_info "  For minikube: minikube addons enable metrics-server"
        log_info "  For other clusters: kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml"
    fi

    kubectl apply -f "${SCRIPT_DIR}/hpa.yaml"
    log_success "HPA deployed successfully"
}

show_deployment_info() {
    log_info "Deployment Summary:"
    echo ""

    log_info "Namespace: ${NAMESPACE}"
    kubectl get all -n "${NAMESPACE}"
    echo ""

    log_info "ConfigMaps:"
    kubectl get configmap -n "${NAMESPACE}"
    echo ""

    log_info "Persistent Volume Claims:"
    kubectl get pvc -n "${NAMESPACE}"
    echo ""

    # Get service URL
    log_info "Service Information:"
    SERVICE_TYPE=$(kubectl get svc rate-limiter-service -n "${NAMESPACE}" -o jsonpath='{.spec.type}')

    if [ "${SERVICE_TYPE}" = "LoadBalancer" ]; then
        log_info "Waiting for LoadBalancer IP..."
        kubectl get svc rate-limiter-service -n "${NAMESPACE}"
        echo ""
        log_info "Access the application using the LoadBalancer IP once available"
    elif [ "${SERVICE_TYPE}" = "NodePort" ]; then
        NODE_PORT=$(kubectl get svc rate-limiter-service -n "${NAMESPACE}" -o jsonpath='{.spec.ports[0].nodePort}')
        log_info "Access the application at: http://<node-ip>:${NODE_PORT}"
    else
        log_info "Use port-forward to access the application:"
        log_info "  kubectl port-forward svc/rate-limiter-service 8080:80 -n ${NAMESPACE}"
        log_info "  Then access: http://localhost:8080"
    fi
    echo ""

    log_success "Deployment completed successfully!"
}

# ============================================
# Main Execution
# ============================================

main() {
    log_info "Starting deployment for environment: ${ENVIRONMENT}"
    echo ""

    check_prerequisites
    echo ""

    create_namespace
    echo ""

    deploy_redis
    echo ""

    deploy_application
    echo ""

    deploy_hpa
    echo ""

    show_deployment_info
}

# Run main function
main
